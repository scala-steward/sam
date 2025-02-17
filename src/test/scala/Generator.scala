package org.broadinstitute.dsde.workbench.sam

import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.api.InviteUser
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.scalacheck._
import SamResourceActions._

object Generator {
  val genNonPetEmail: Gen[WorkbenchEmail] = Gen.alphaStr.map(x => WorkbenchEmail(s"t$x@gmail.com"))
  val genPetEmail: Gen[WorkbenchEmail] = Gen.alphaStr.map(x => WorkbenchEmail(s"t$x@test.iam.gserviceaccount.com"))
  val genGoogleSubjectId: Gen[GoogleSubjectId] = Gen.stringOfN(20, Gen.numChar).map(id => GoogleSubjectId("1" + id))
  val genAzureB2CId: Gen[AzureB2CId] = Gen.uuid.map(uuid => AzureB2CId(uuid.toString))
  val genExternalId: Gen[Either[GoogleSubjectId, AzureB2CId]] = Gen.either(genGoogleSubjectId, genAzureB2CId)
  val genServiceAccountSubjectId: Gen[ServiceAccountSubjectId] = genGoogleSubjectId.map(x => ServiceAccountSubjectId(x.value))
  val genOAuth2BearerToken: Gen[OAuth2BearerToken] = Gen.alphaStr.map(x => OAuth2BearerToken("s"+x))

  val genHeadersWithoutExpiresIn: Gen[List[RawHeader]] = for{
    email <- genNonPetEmail
    accessToken <- genOAuth2BearerToken
    externalId <- genExternalId
  } yield List(
    RawHeader(emailHeader, email.value),
    RawHeader(userIdHeader, externalId.fold(_.value, _.value)),
    RawHeader(accessTokenHeader, accessToken.value)
  )

  val genUserInfoHeadersWithInvalidExpiresIn: Gen[List[RawHeader]] = for{
    ls <- genHeadersWithoutExpiresIn
    expiresIn <- Gen.alphaStr.map(x => s"s$x")
  } yield RawHeader(expiresInHeader, expiresIn) :: ls

  val genValidUserInfoHeaders: Gen[List[RawHeader]] = for{
     ls <- genHeadersWithoutExpiresIn
  } yield RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString) :: ls

  val genUserInfo = for{
    token <- genOAuth2BearerToken
    email <- genNonPetEmail
    expires <- Gen.calendar.map(_.getTimeInMillis)
  } yield UserInfo(token, genWorkbenchUserId(System.currentTimeMillis()), email, expires)

  val genWorkbenchUserGoogle = for{
    email <- genNonPetEmail
    googleSubjectId <- genGoogleSubjectId
    userId = genWorkbenchUserId(System.currentTimeMillis())
  }yield WorkbenchUser(userId, Option(googleSubjectId), email, None)

  val genWorkbenchUserAzure = for {
    email <- genNonPetEmail
    azureB2CId <- genAzureB2CId
    userId = genWorkbenchUserId(System.currentTimeMillis())
  } yield WorkbenchUser(userId, None, email, Option(azureB2CId))

  val genInviteUser = for{
    email <- genNonPetEmail
    userId = genWorkbenchUserId(System.currentTimeMillis())
  }yield InviteUser(userId, email)

  val genWorkbenchGroupName = Gen.alphaStr.map(x => WorkbenchGroupName(s"s${x.take(50)}")) //prepending `s` just so this won't be an empty string
  val genGoogleProject = Gen.alphaStr.map(x => GoogleProject(s"s$x")) //prepending `s` just so this won't be an empty string
  val genWorkbenchSubject: Gen[WorkbenchSubject] = for{
    groupId <- genWorkbenchGroupName
    project <- genGoogleProject
    res <- Gen.oneOf[WorkbenchSubject](List(genWorkbenchUserId(System.currentTimeMillis()), groupId))
  }yield res

  val genBasicWorkbenchGroup = for{
    id <- genWorkbenchGroupName
    subject <- Gen.listOf[WorkbenchSubject](genWorkbenchSubject).map(_.toSet)
    email <- genNonPetEmail
  } yield BasicWorkbenchGroup(id, subject, email)

  val genResourceTypeName: Gen[ResourceTypeName] = Gen.oneOf("workspace", "managed-group", "workflow-collection", "caas", "billing-project", "notebook-cluster", "cloud-extension", "dockstore-tool", "entity-collection").map(ResourceTypeName.apply)
  val genResourceTypeNameExcludeManagedGroup: Gen[ResourceTypeName] = Gen.oneOf("workspace", "workflow-collection", "caas", "billing-project", "notebook-cluster", "cloud-extension", "dockstore-tool", "entity-collection").map(ResourceTypeName.apply)
  val genResourceId : Gen[ResourceId] = Gen.uuid.map(x => ResourceId(x.toString))
  val genAccessPolicyName : Gen[AccessPolicyName] = Gen.oneOf("member", "admin", "admin-notifier").map(AccessPolicyName.apply) //there might be possible values
  def genAuthDomains: Gen[Set[WorkbenchGroupName]] = Gen.listOfN[ResourceId](3, genResourceId).map(x => x.map(v => WorkbenchGroupName(v.value)).toSet) //make it a list of 3 items so that unit test won't time out
  val genNonEmptyAuthDomains: Gen[Set[WorkbenchGroupName]] = Gen.nonEmptyListOf[ResourceId](genResourceId).map(x => x.map(v => WorkbenchGroupName(v.value)).toSet)
  val genRoleName: Gen[ResourceRoleName] = Gen.oneOf("owner", "other").map(ResourceRoleName.apply) //there might be possible values
  val genResourceAction: Gen[ResourceAction] = Gen.oneOf(readPolicies, alterPolicies, delete, notifyAdmins, setAccessInstructions)

  val genResource: Gen[Resource] = for{
    resourceType <- genResourceTypeName
    id <- genResourceId
    authDomains <- genAuthDomains
  } yield Resource(resourceType, id, authDomains)

  val genResourceIdentity: Gen[FullyQualifiedResourceId] = for{
    resourceType <- genResourceTypeName
    id <- genResourceId
  } yield model.FullyQualifiedResourceId(resourceType, id)

  val genPolicyIdentity: Gen[FullyQualifiedPolicyId] = for{
    r <- genResourceIdentity
    policyName <- genAccessPolicyName
  } yield FullyQualifiedPolicyId(r, policyName)

  val genPolicy: Gen[AccessPolicy] = for{
    id <- genPolicyIdentity
    members <- Gen.listOf(genWorkbenchSubject).map(_.toSet)
    email <- genNonPetEmail
    roles <- Gen.listOf(genRoleName).map(_.toSet)
    actions <- Gen.listOf(genResourceAction).map(_.toSet)
  } yield AccessPolicy(id, members, email, roles, actions, Set.empty, public = false)

  implicit val arbNonPetEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genNonPetEmail)
  implicit val arbOAuth2BearerToken: Arbitrary[OAuth2BearerToken] = Arbitrary(genOAuth2BearerToken)
  implicit val arbWorkbenchUser: Arbitrary[WorkbenchUser] = Arbitrary(genWorkbenchUserGoogle)
  implicit val arbPolicy: Arbitrary[AccessPolicy] = Arbitrary(genPolicy)
  implicit val arbResource: Arbitrary[Resource] = Arbitrary(genResource)
  implicit val arbGoogleSubjectId: Arbitrary[GoogleSubjectId] = Arbitrary(genGoogleSubjectId)
  implicit val arbAzureB2CId: Arbitrary[AzureB2CId] = Arbitrary(genAzureB2CId)
}
