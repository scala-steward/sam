package org.broadinstitute.dsde.workbench.sam.service

import java.net.URI
import java.util.UUID
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.AppConfig.resourceTypeReader
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, PostgresAccessPolicyDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends AnyFlatSpec with Matchers with ScalaFutures with TestSupport with BeforeAndAfter with BeforeAndAfterAll with MockitoSugar {
  val directoryConfig = TestSupport.directoryConfig
  val schemaLockConfig = TestSupport.schemaLockConfig
  //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val realResourceTypes = TestSupport.appConfig.resourceTypes
  private val realResourceTypeMap = realResourceTypes.map(rt => rt.name -> rt).toMap

  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  lazy val policyDAO: AccessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val ownerRoleName = ResourceRoleName("owner")

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  private val defaultResourceTypeActions = Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), ResourceAction("view"), ResourceAction("non_owner_action"))
  private val defaultResourceTypeActionPatterns = Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.delete, SamResourceActionPatterns.readPolicies, ResourceActionPattern("view", "", false), ResourceActionPattern("non_owner_action", "", false))
  private val defaultResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActionPatterns, Set(ResourceRole(ownerRoleName, defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ownerRoleName)
  private val otherResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActionPatterns, Set(ResourceRole(ownerRoleName, defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ownerRoleName)
  private val childResourceTypeName = ResourceTypeName("child-resource-type")
  private val childResourceTypeOwnerRole = ResourceRole(ownerRoleName, defaultResourceTypeActions - ResourceAction("non_owner_action"))
  private val childResourceTypeOtherRole = ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
  private val childResourceType = ResourceType(childResourceTypeName, defaultResourceTypeActionPatterns, Set(childResourceTypeOwnerRole, childResourceTypeOtherRole), childResourceTypeOwnerRole.roleName)
  private val parentResourceTypeOwnerRole = ResourceRole(ownerRoleName, defaultResourceTypeActions - ResourceAction("non_owner_action"), Set(), Map(childResourceTypeName -> Set(childResourceTypeOwnerRole.roleName)))
  private val parentResourceTypeOtherRole = ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
  private val parentResourceTypeRoles = Set(parentResourceTypeOwnerRole, parentResourceTypeOtherRole)
  private val parentResourceType = ResourceType(ResourceTypeName("parent-resource-type"), defaultResourceTypeActionPatterns, parentResourceTypeRoles, ownerRoleName)
  val otherParentResourceType: ResourceType = parentResourceType.copy(name = ResourceTypeName("parent-resource-type-2"))

  private val constrainableActionPatterns = Set(ResourceActionPattern("constrainable_view", "Can be constrained by an auth domain", true))
  private val constrainableViewAction = ResourceAction("constrainable_view")
  private val constrainableResourceTypeActions = Set(constrainableViewAction)
  private val constrainableReaderRoleName = ResourceRoleName("constrainable_reader")
  private val constrainableResourceType = ResourceType(
    genResourceTypeNameExcludeManagedGroup.sample.get,
    constrainableActionPatterns,
    Set(ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)),
    constrainableReaderRoleName
  )
  private val constrainablePolicyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(constrainableViewAction), Set(constrainableReaderRoleName), None)

  private val managedGroupResourceType = realResourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

  private val emailDomain = "example.com"
  private val resourceTypes = Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType, parentResourceType.name -> parentResourceType, childResourceType.name -> childResourceType, managedGroupResourceType.name -> managedGroupResourceType, otherParentResourceType.name -> otherParentResourceType)
  private val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypes, policyDAO, dirDAO)
  private val service = new ResourceService(resourceTypes, policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
  private val constrainableResourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
  private val constrainablePolicyEvaluatorService = PolicyEvaluatorService(emailDomain, constrainableResourceTypes, policyDAO, dirDAO)
  private val constrainableService = new ResourceService(constrainableResourceTypes, constrainablePolicyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

  val managedGroupService = new ManagedGroupService(constrainableService, constrainablePolicyEvaluatorService, constrainableResourceTypes, policyDAO, dirDAO, NoExtensions, emailDomain)

  private object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val sharePolicy = ResourceActionPattern("share_policy::.+", "", false)
    val readPolicy = ResourceActionPattern("read_policy::.+", "", false)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    clearDatabase()
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, TestSupport.genGoogleSubjectId(), dummyUserInfo.userEmail, Some(TestSupport.genAzureB2CId())), samRequestContext).unsafeRunSync()
  }

  protected def clearDatabase(): Unit = TestSupport.truncateAll

  def toEmail(resourceType: String, resourceName: String, policyName: String) = {
    WorkbenchEmail("policy-randomuuid@example.com")
  }

  private def constructExpectedPolicies(resourceType: ResourceType, resource: FullyQualifiedResourceId) = {
    val role = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).get
    val initialMembers = if(role.roleName.equals(resourceType.ownerRoleName)) Set(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]) else Set[WorkbenchSubject]()
    val group = BasicWorkbenchGroup(WorkbenchGroupName(role.roleName.value), initialMembers, toEmail(resource.resourceTypeName.value, resource.resourceId.value, role.roleName.value))
    LazyList(AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName(role.roleName.value)), group.members, group.email, Set(role.roleName), Set.empty, Set.empty, public = false))
  }

  "ResourceType config" should "allow constraining policies to an auth domain" in {
    val resourceTypes = TestSupport.config.as[Map[String, ResourceType]]("testStuff.resourceTypes").values.toSet
    val rt = resourceTypes.find(_.name == ResourceTypeName("testType")).getOrElse(fail("Missing test resource type, please check src/test/resources/reference.conf"))
    val constrainedAction = rt.actionPatterns.find(_.value == "alter_policies").getOrElse(fail("Missing action pattern, please check src/test/resources/reference.conf"))
    constrainedAction.authDomainConstrainable shouldEqual true
  }

  it should "read included and descendant roles" in {
    val testResourceTypes = TestSupport.config.as[Map[String, ResourceType]]("testStuff.resourceTypes").values.toSet
    val testType = testResourceTypes.find(_.name == ResourceTypeName("testType")).getOrElse(fail("Missing test resource type, please check src/test/resources/reference.conf"))
    val nonOwnerRoleName = ResourceRoleName("nonOwner")
    val expectedRoles = Set(
      ResourceRole(ownerRoleName,
        Set(ResourceAction("read_policies"), ResourceAction("alter_policies")),
        Set(nonOwnerRoleName),
        Map(ResourceTypeName("otherType") -> Set(ownerRoleName))),
      ResourceRole(nonOwnerRoleName, Set.empty))

    testType.roles should contain theSameElementsAs expectedRoles
  }

  "ResourceService" should "create and delete resource" in {
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()

    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo, samRequestContext))

    assertResult(constructExpectedPolicies(defaultResourceType, resource)) {
      policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))
    }

    //cleanup
    runAndWait(service.deleteResource(resource, samRequestContext))

    assertResult(LazyList.empty) {
      policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()
    }
  }

  it should "set public policies" in {
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()

    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo, samRequestContext))

    val policyToUpdate = constructExpectedPolicies(defaultResourceType, resource).head.id

    service.isPublic(policyToUpdate, samRequestContext).unsafeRunSync() should equal(false)

    service.setPublic(policyToUpdate, true, samRequestContext).unsafeRunSync()
    service.isPublic(policyToUpdate, samRequestContext).unsafeRunSync() should equal(true)

    service.setPublic(policyToUpdate, false, samRequestContext).unsafeRunSync()
    service.isPublic(policyToUpdate, samRequestContext).unsafeRunSync() should equal(false)

    //cleanup
    runAndWait(service.deleteResource(resource, samRequestContext))
  }

  it should "fail to set public policies on auth domained resources" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()

    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(constrainableResourceType.name, resourceName)

    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()
    val managedGroupResource = managedGroupService.createManagedGroup(ResourceId("ad"), dummyUserInfo, samRequestContext = samRequestContext).unsafeRunSync()

    val ownerRoleName = constrainableReaderRoleName
    val policyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(constrainableViewAction), Set(ownerRoleName), None)
    val policyName = AccessPolicyName("foo")

    val testResource = runAndWait(constrainableService.createResource(constrainableResourceType, resourceName, Map(policyName -> policyMembership), Set(WorkbenchGroupName(managedGroupResource.resourceId.value)), None, dummyUserInfo.userId, samRequestContext))

    val policyToUpdate = FullyQualifiedPolicyId(testResource.fullyQualifiedId, policyName)
    constrainableService.isPublic(policyToUpdate, samRequestContext).unsafeRunSync() should equal(false)

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      constrainableService.setPublic(policyToUpdate, true, samRequestContext).unsafeRunSync()
    }

    err.errorReport.statusCode should equal(Some(StatusCodes.BadRequest))
  }

  "listUserResourceActions" should "list the user's actions for a resource" in {
    val otherRoleName = ResourceRoleName("other")
    val resourceName1 = ResourceId("resource1")
    val resourceName2 = ResourceId("resource2")

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    service.createResource(defaultResourceType, resourceName1, dummyUserInfo, samRequestContext).unsafeRunSync()

    val policies2 = service.createResource(defaultResourceType, resourceName2, dummyUserInfo, samRequestContext).unsafeRunSync()

    policyDAO.createPolicy(AccessPolicy(
      FullyQualifiedPolicyId(policies2.fullyQualifiedId, AccessPolicyName(otherRoleName.value)), Set(dummyUserInfo.userId), WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty, Set.empty, public = false), samRequestContext).unsafeRunSync()

    assertResult(defaultResourceType.roles.filter(_.roleName.equals(ownerRoleName)).head.actions) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), dummyUserInfo.userId, samRequestContext = samRequestContext).unsafeRunSync()
    }

    assertResult(defaultResourceTypeActions) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), dummyUserInfo.userId, samRequestContext = samRequestContext).unsafeRunSync()
    }

    assert(!service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), ResourceAction("non_owner_action"), dummyUserInfo.userId, samRequestContext).unsafeRunSync())
    assert(service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), ResourceAction("non_owner_action"), dummyUserInfo.userId, samRequestContext).unsafeRunSync())
    assert(!service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("doesnotexist")), ResourceAction("view"), dummyUserInfo.userId, samRequestContext).unsafeRunSync())
  }

  it should "list the user's actions for a resource with nested groups" in {
    val resourceName1 = ResourceId("resource1")

    val user = dirDAO.createUser(WorkbenchUser(WorkbenchUserId("asdfawefawea"), None, WorkbenchEmail("asdfawefawea@foo.bar"), None), samRequestContext).unsafeRunSync()
    val group = BasicWorkbenchGroup(WorkbenchGroupName("g"), Set(user.id), WorkbenchEmail("foo@bar.com"))
    dirDAO.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    val resource = runAndWait(service.createResource(defaultResourceType, resourceName1, dummyUserInfo, samRequestContext))
    val nonOwnerAction = ResourceAction("non_owner_action")
    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("new_policy"), resource.fullyQualifiedId, AccessPolicyMembership(Set(group.email), Set(nonOwnerAction), Set.empty, None), samRequestContext))

    val userInfo = UserInfo(OAuth2BearerToken(""), user.id, user.email, 0)
    assertResult(Set(ResourceAction("non_owner_action"))) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), userInfo.userId, samRequestContext = samRequestContext).unsafeRunSync()
    }

    assert(service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), nonOwnerAction, userInfo.userId, samRequestContext).unsafeRunSync())
  }

  it should "list public policies" in {
    val otherRoleName = ResourceRoleName("other")
    val resourceName2 = ResourceId("resource2")

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()

    val policies2 = runAndWait(service.createResource(defaultResourceType, resourceName2, dummyUserInfo, samRequestContext))

    policyDAO.createPolicy(AccessPolicy(
      FullyQualifiedPolicyId(policies2.fullyQualifiedId, AccessPolicyName(otherRoleName.value)), Set.empty, WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty, Set.empty, public = true), samRequestContext).unsafeRunSync()

    assertResult(defaultResourceTypeActions) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), dummyUserInfo.userId, samRequestContext = samRequestContext).unsafeRunSync()
    }

    assert(service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), ResourceAction("non_owner_action"), dummyUserInfo.userId, samRequestContext).unsafeRunSync())
  }

  "createResource" should "detect conflict on create" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(resourceType, resourceName, dummyUserInfo, samRequestContext))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, dummyUserInfo, samRequestContext))
    }

    exception.errorReport.statusCode shouldEqual Option(StatusCodes.Conflict)
  }

  it should "create resource with custom policies" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType, samRequestContext).unsafeRunSync()

    val policyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("view")), Set(ownerRoleName), Option(Set.empty))
    val policyName = AccessPolicyName("foo")

    runAndWait(service.createResource(resourceType, resourceName, Map(policyName -> policyMembership), Set.empty, None, dummyUserInfo.userId, samRequestContext))

    val policies = service.listResourcePolicies(FullyQualifiedResourceId(resourceType.name, resourceName), samRequestContext).unsafeRunSync()
    assertResult(LazyList(AccessPolicyResponseEntry(policyName, policyMembership, WorkbenchEmail("")))) {
      policies.map(_.copy(email = WorkbenchEmail("")))
    }
  }

  it should "support valid resource ids" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)

    service.createResourceType(resourceType, samRequestContext).unsafeRunSync()

    val validChars = ('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ Seq('.', '-', '_', '~', '%')
    runAndWait(service.createResource(resourceType, ResourceId(validChars.mkString), dummyUserInfo, samRequestContext))
  }

  it should "prevent invalid resource ids" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)

    service.createResourceType(resourceType, samRequestContext).unsafeRunSync()

    for (char <- "!@#$^&*()+= <>/?'\"][{}\\|`") {
      withClue(s"expected character $char to be invalid") {
        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(service.createResource(resourceType, ResourceId(char.toString), dummyUserInfo, samRequestContext))
        }

        exception.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)
      }
    }
  }

  it should "prevent ownerless resource" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType, samRequestContext).unsafeRunSync()

    val exception1 = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, Map(AccessPolicyName("foo") -> AccessPolicyMembership(Set.empty, Set.empty, Set(ownerRoleName), None)), Set.empty, None, dummyUserInfo.userId, samRequestContext))
    }

    exception1.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)

    val exception2 = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, Map(AccessPolicyName("foo") -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set.empty, None)), Set.empty, None, dummyUserInfo.userId, samRequestContext))
    }

    exception2.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)
  }

  it should "create ownerless resource with parent" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set.empty, Set(ResourceRole(ownerRoleName, Set.empty)), ownerRoleName)
    val parentResourceName = ResourceId("parent")
    val childResourceName = ResourceId("child")

    val test = for {
      _ <- service.createResourceType(resourceType, samRequestContext)
      parent <- service.createResource(resourceType, parentResourceName, dummyUserInfo, samRequestContext)
      child <- service.createResource(resourceType, childResourceName, Map.empty, Set.empty, Option(parent.fullyQualifiedId), dummyUserInfo.userId, samRequestContext)
      actualParent <- policyDAO.getResourceParent(child.fullyQualifiedId, samRequestContext)
    } yield {
      actualParent shouldBe Option(parent.fullyQualifiedId)
    }

    test.unsafeRunSync()
  }

  private def assertResourceExists(
      resource: FullyQualifiedResourceId, resourceType: ResourceType, policyDao: AccessPolicyDAO) = {
    val resultingPolicies = policyDao.listAccessPolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email = WorkbenchEmail("policy-randomuuid@example.com")))
    resultingPolicies should contain theSameElementsAs constructExpectedPolicies(resourceType, resource)
  }

  "Creating a resource that has at least 1 constrainable action pattern" should "succeed when no auth domain is provided" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()

    val resource = runAndWait(service.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), dummyUserInfo, samRequestContext))
    assertResourceExists(resource.fullyQualifiedId, constrainableResourceType, policyDAO)
  }

  it should "succeed when at least 1 valid auth domain group is provided" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()
    val managedGroupName = "fooGroup"
    val secondMGroupName = "barGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo, samRequestContext = samRequestContext))
    runAndWait(managedGroupService.createManagedGroup(ResourceId(secondMGroupName), dummyUserInfo, samRequestContext = samRequestContext))

    val authDomain = NonEmptyList.of(WorkbenchGroupName(managedGroupName), WorkbenchGroupName(secondMGroupName))
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain.toList.toSet, None, dummyUserInfo.userId, samRequestContext))
    val storedAuthDomain = constrainableService.loadResourceAuthDomain(resource.fullyQualifiedId, samRequestContext).unsafeRunSync()

    storedAuthDomain should contain theSameElementsAs authDomain.toList
  }

  it should "fail when at least 1 of the auth domain groups does not exist" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()
    val managedGroupName = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo, samRequestContext = samRequestContext))
    val nonExistentGroup = WorkbenchGroupName("aBadGroup")

    val authDomain = Set(WorkbenchGroupName(managedGroupName), nonExistentGroup)
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, None, dummyUserInfo.userId, samRequestContext))
    }
  }

  it should "fail when user does not have access to at least 1 of the auth domain groups" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()

    val bender = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("Bender"), WorkbenchEmail("bender@planex.com"), 0)
    dirDAO.createUser(WorkbenchUser(bender.userId, None, bender.userEmail, None), samRequestContext).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()
    val managedGroupName1 = "firstGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName1), dummyUserInfo, samRequestContext = samRequestContext))
    val managedGroupName2 = "benderIsGreat"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName2), bender, samRequestContext = samRequestContext))

    val authDomain = Set(WorkbenchGroupName(managedGroupName1), WorkbenchGroupName(managedGroupName2))
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, None, dummyUserInfo.userId, samRequestContext))
    }
  }

  "Loading an auth domain" should "fail when the resource does not exist" in {
    val e = intercept[WorkbenchExceptionWithErrorReport] {
      constrainableService.loadResourceAuthDomain(FullyQualifiedResourceId(constrainableResourceType.name, ResourceId(UUID.randomUUID().toString)), samRequestContext).unsafeRunSync()
    }
    e.getMessage should include ("not found")
  }

  "Creating a resource that has 0 constrainable action patterns" should "fail when an auth domain is provided" in {
    defaultResourceType.isAuthDomainConstrainable shouldEqual false
    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()
    val managedGroupName = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo, samRequestContext = samRequestContext))

    val policyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("view")), Set(ownerRoleName), None)
    val policyName = AccessPolicyName("foo")

    val authDomain = Set(WorkbenchGroupName(managedGroupName))
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(defaultResourceType, ResourceId(UUID.randomUUID().toString), Map(policyName -> policyMembership), authDomain, None, dummyUserInfo.userId, samRequestContext))
    }
  }

  "listUserResourceRoles" should "list the user's role when they have at least one role" in {
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo, samRequestContext))

    val roles = runAndWait(service.listUserResourceRoles(resource, dummyUserInfo, samRequestContext))

    roles shouldEqual Set(ownerRoleName)
  }

  it should "return an empty set when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("a1", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    val roles = runAndWait(service.listUserResourceRoles(FullyQualifiedResourceId(resourceType.name, resourceName), dummyUserInfo, samRequestContext))

    roles shouldEqual Set.empty

    dirDAO.deleteUser(dummyUserInfo.userId, samRequestContext).unsafeRunSync()
  }

  "policyDao.listAccessPolicies" should "list policies for a newly created resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    constructExpectedPolicies(defaultResourceType, resource) should contain theSameElementsAs(policies)
  }

  "listResourcePolicies" should "list policies for a newly created resource without member email addresses if the User does not exist" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName), Option(Set.empty)), forcedEmail)

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val policies = service.listResourcePolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email = forcedEmail))
    policies should contain theSameElementsAs Set(expectedPolicy)
  }

  it should "list policies for a newly created resource with the member email addresses if the User has been added" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName), Option(Set.empty)), forcedEmail)

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val policies = service.listResourcePolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email = forcedEmail))
    policies should contain theSameElementsAs Set(expectedPolicy)
  }

  it should "include memberPolicies in the policy list where applicable" in {
    // create a "side" resource; we will use its policy emails as members in the default resource
    val sideResource = FullyQualifiedResourceId(otherResourceType.name, ResourceId("side-resource"))
    service.createResourceType(otherResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(otherResourceType, sideResource.resourceId, dummyUserInfo, samRequestContext))
    val sidePolicies = service.listResourcePolicies(sideResource, samRequestContext).unsafeRunSync()

    // create the default resource
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    // add "side" policy emails to default resource
    sidePolicies.foreach { sidePolicy =>
      dirDAO.loadSubjectFromEmail(sidePolicy.email, samRequestContext).unsafeRunSync().map { subj =>
        service.addSubjectToPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName(defaultResourceType.ownerRoleName.value)), subj, samRequestContext).unsafeRunSync()
      }
    }

    val actualPolicies = service.listResourcePolicies(resource, samRequestContext).unsafeRunSync()
    actualPolicies.size shouldBe 1
    val ownerPolicy = actualPolicies.head

    val expectedMemberPolicies = sidePolicies.map { p =>
      PolicyIdentifiers(p.policyName, p.email, sideResource.resourceTypeName, sideResource.resourceId)
    }

    ownerPolicy.policy.memberPolicies.value shouldBe expectedMemberPolicies.toSet

    // all memberPolicies emails should also be in the memberEmails array
    expectedMemberPolicies.foreach { p =>
      ownerPolicy.policy.memberEmails should contain (p.policyEmail)
    }
  }

  it should "not include memberPolicies in the policy list if no member is a policy" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val actualPolicies = service.listResourcePolicies(resource, samRequestContext).unsafeRunSync()
    actualPolicies.size shouldBe 1
    // expect an empty set
    actualPolicies.head.policy.memberPolicies shouldBe defined
    actualPolicies.head.policy.memberPolicies.get shouldBe empty
  }

  "overwritePolicy" should "succeed with a valid request" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, public = false)

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, None), samRequestContext))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))
  }

  it should "call CloudExtensions.onGroupUpdate when members change" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mockAccessPolicyDAO,
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val member = WorkbenchUserId("testU")
    val accessPolicy = AccessPolicy(policyId, Set.empty, WorkbenchEmail(""), Set.empty, Set.empty, Set.empty, false)

    // setup existing policy with a member
    when(mockAccessPolicyDAO.loadPolicy(ArgumentMatchers.eq(policyId), any[SamRequestContext])).thenReturn(IO.pure(Option(AccessPolicy.members.set(Set(member))(accessPolicy))))

    // function calls that should pass but what they return does not matter
    when(mockAccessPolicyDAO.overwritePolicy(ArgumentMatchers.eq(accessPolicy), any[SamRequestContext])).thenReturn(IO.pure(accessPolicy))
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))

    // overwrite policy with no members
    runAndWait(resourceService.overwritePolicy(defaultResourceType, policyId.accessPolicyName, policyId.resource, AccessPolicyMembership(Set.empty, Set.empty, Set.empty), samRequestContext))

    verify(mockCloudExtensions, Mockito.timeout(500)).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  it should "not call CloudExtensions.onGroupUpdate when members don't change" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mockAccessPolicyDAO,
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val accessPolicy = AccessPolicy(policyId, Set.empty, WorkbenchEmail(""), Set.empty, Set.empty, Set.empty, false)

    // setup existing policy with no members
    when(mockAccessPolicyDAO.loadPolicy(ArgumentMatchers.eq(policyId), any[SamRequestContext])).thenReturn(IO.pure(Option(accessPolicy)))

    // function calls that should pass but what they return does not matter
    when(mockAccessPolicyDAO.overwritePolicy(ArgumentMatchers.eq(accessPolicy), any[SamRequestContext])).thenReturn(IO.pure(accessPolicy))
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))

    // overwrite policy with no members
    runAndWait(resourceService.overwritePolicy(defaultResourceType, policyId.accessPolicyName, policyId.resource, AccessPolicyMembership(Set.empty, Set.empty, Set.empty), samRequestContext))

    verify(mockCloudExtensions, Mockito.after(500).never).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  "overwritePolicyMembers" should "succeed with a valid request" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, public = false)

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, None), samRequestContext))

    runAndWait(service.overwritePolicyMembers(newPolicy.id, Set.empty, samRequestContext))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))
  }

  it should "call CloudExtensions.onGroupUpdate when members change" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mockAccessPolicyDAO,
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val member = WorkbenchUserId("testU")

    // setup existing policy with a member
    when(mockAccessPolicyDAO.loadPolicy(ArgumentMatchers.eq(policyId), any[SamRequestContext])).thenReturn(IO.pure(Option(AccessPolicy(policyId, Set(member), WorkbenchEmail(""), Set.empty, Set.empty, Set.empty, false))))

    // function calls that should pass but what they return does not matter
    when(mockAccessPolicyDAO.overwritePolicyMembers(ArgumentMatchers.eq(policyId), ArgumentMatchers.eq(Set.empty), any[SamRequestContext])).thenReturn(IO.unit)
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))

    // overwrite policy members with empty set
    runAndWait(resourceService.overwritePolicyMembers(policyId, Set.empty, samRequestContext))

    verify(mockCloudExtensions, Mockito.timeout(500)).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  it should "not call CloudExtensions.onGroupUpdate when members don't change" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mockAccessPolicyDAO,
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))

    // setup existing policy with no members
    when(mockAccessPolicyDAO.loadPolicy(ArgumentMatchers.eq(policyId), any[SamRequestContext])).thenReturn(IO.pure(Option(AccessPolicy(policyId, Set.empty, WorkbenchEmail(""), Set.empty, Set.empty, Set.empty, false))))

    // function calls that should pass but what they return does not matter
    when(mockAccessPolicyDAO.overwritePolicyMembers(ArgumentMatchers.eq(policyId), ArgumentMatchers.eq(Set.empty), any[SamRequestContext])).thenReturn(IO.unit)
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))

    // overwrite policy members with empty set
    runAndWait(resourceService.overwritePolicyMembers(policyId, Set.empty, samRequestContext))

    verify(mockCloudExtensions, Mockito.after(500).never).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  it should "succeed with a regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("foo-biz-bar")))), ownerRoleName
    )
    val resource = FullyQualifiedResourceId(rt.name, ResourceId("my-resource"))

    service.createResourceType(rt, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo, samRequestContext))

    val actions = Set(ResourceAction("foo-bang-bar"))
    val newPolicy = runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, actions, Set.empty, None), samRequestContext))

    assertResult(actions) {
      newPolicy.actions
    }

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()

    assert(policies.contains(newPolicy))
  }

  it should "fail when given an invalid action" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty, public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty, None), samRequestContext))
    }

    assert(exception.getMessage.contains("invalid action"))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("foo-biz-bar")))), ownerRoleName
    )
    val resource = FullyQualifiedResourceId(rt.name, ResourceId("my-resource"))

    service.createResourceType(rt, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo, samRequestContext))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("foo--bar")), Set.empty, None), samRequestContext))
    }

    assert(exception.getMessage.contains("invalid action"))
  }

  it should "fail when given an invalid role" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set(ResourceRoleName("INVALID_ROLE")), Set.empty, Set.empty, public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set.empty, Set(ResourceRoleName("INVALID_ROLE")), None), samRequestContext))
    }

    assert(exception.getMessage.contains("invalid role"))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid member email" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set(WorkbenchEmail("null@null.com")), Set.empty, Set.empty, None), samRequestContext))
    }

    assert(exception.getMessage.contains("invalid member email"))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid name" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName("foo?bar")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty, None), samRequestContext))
    }

    assert(exception.getMessage.contains("Invalid input"))

    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  "deleteResource" should "delete the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    assert(policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync().nonEmpty)

    runAndWait(service.deleteResource(resource, samRequestContext))

    assert(policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync().isEmpty)
  }

  it should "not allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is false for the Resource Type" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    defaultResourceType.reuseIds shouldEqual false

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.deleteResource(resource, samRequestContext))

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))
    }
    err.getMessage should include ("resource of this type and name already exists")
  }

  it should "allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is true for the Resource Type" in {
    val reusableResourceType = defaultResourceType.copy(reuseIds = true)
    reusableResourceType.reuseIds shouldEqual true
    val localService = new ResourceService(Map(reusableResourceType.name -> reusableResourceType), null, policyDAO, dirDAO, NoExtensions, "example.com")

    localService.createResourceType(reusableResourceType, samRequestContext).unsafeRunSync()

    val resource = FullyQualifiedResourceId(reusableResourceType.name, ResourceId("my-resource"))

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, dummyUserInfo, samRequestContext))
    policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync() should not be empty

    runAndWait(localService.deleteResource(resource, samRequestContext))
    policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync() shouldBe empty

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, dummyUserInfo, samRequestContext))
    policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync() should not be empty
  }

  it should "allow for auth domain groups on a deleted resource to be deleted" in {
    val resourceType = constrainableResourceType.copy(reuseIds = false)
    val authDomainGroupToDelete = "fooGroup"
    val otherAuthDomainGroup = "barGroup"
    constrainableService.createResourceType(resourceType, samRequestContext).unsafeRunSync()
    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()
    managedGroupService.createManagedGroup(ResourceId(authDomainGroupToDelete), dummyUserInfo, samRequestContext = samRequestContext).unsafeRunSync()
    managedGroupService.createManagedGroup(ResourceId(otherAuthDomainGroup), dummyUserInfo, samRequestContext = samRequestContext).unsafeRunSync()
    val resourceToDelete = constrainableService.createResource(resourceType, ResourceId(UUID.randomUUID().toString), Map(AccessPolicyName(constrainableReaderRoleName.value) -> constrainablePolicyMembership), Set(WorkbenchGroupName(authDomainGroupToDelete)), None, dummyUserInfo.userId, samRequestContext).unsafeRunSync()
    val otherResource = constrainableService.createResource(resourceType, ResourceId(UUID.randomUUID().toString), Map(AccessPolicyName(constrainableReaderRoleName.value) -> constrainablePolicyMembership), Set(WorkbenchGroupName(otherAuthDomainGroup)), None, dummyUserInfo.userId, samRequestContext).unsafeRunSync()

    runAndWait(constrainableService.deleteResource(resourceToDelete.fullyQualifiedId, samRequestContext))
    runAndWait(managedGroupService.deleteManagedGroup(ResourceId(authDomainGroupToDelete), samRequestContext))
    managedGroupService.loadManagedGroup(ResourceId(authDomainGroupToDelete), samRequestContext).unsafeRunSync() shouldBe None

    // Other constrained resources and managed groups should be unaffected
    constrainableService.loadResourceAuthDomain(otherResource.fullyQualifiedId, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(WorkbenchGroupName(otherAuthDomainGroup))
    managedGroupService.loadManagedGroup(ResourceId(otherAuthDomainGroup), samRequestContext).unsafeRunSync() shouldBe Some(WorkbenchEmail(s"$otherAuthDomainGroup@$emailDomain"))
  }

  it should "delete a child resource that has a parent - reuse ids is false" in {
    assert(!defaultResourceType.reuseIds)
    testDeleteResource(defaultResourceType)
  }

  it should "delete a child resource that has a parent - reuse ids is true" in {
    assert(managedGroupResourceType.reuseIds)
    testDeleteResource(managedGroupResourceType)
  }

  private def testDeleteResource(resourceType: ResourceType) = {
    val parentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("my-resource-parent"))
    val childResource = FullyQualifiedResourceId(resourceType.name, ResourceId("my-resource-child"))
    service.createResourceType(resourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(resourceType, parentResource.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.createResource(resourceType, childResource.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.setResourceParent(childResource, parentResource, samRequestContext))

    runAndWait(service.createPolicy(FullyQualifiedPolicyId(parentResource, AccessPolicyName("reader")), Set.empty, Set.empty, Set.empty, Set(AccessPolicyDescendantPermissions(resourceType.name, defaultResourceTypeActions, Set.empty)), samRequestContext))

    assert(policyDAO.listResourceChildren(parentResource, samRequestContext).unsafeRunSync().nonEmpty)

    runAndWait(service.deleteResource(childResource, samRequestContext))

    assert(policyDAO.listResourceChildren(parentResource, samRequestContext).unsafeRunSync().isEmpty)
    assert(policyDAO.listAccessPolicies(childResource, samRequestContext).unsafeRunSync().isEmpty)
  }

  it should "fail deleting a parent resource that has children" in {
    // create a resource with a child
    val parentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource-parent"))
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource-child"))
    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, parentResource.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.createResource(defaultResourceType, childResource.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.setResourceParent(childResource, parentResource, samRequestContext))

    assert(policyDAO.listAccessPolicies(parentResource, samRequestContext).unsafeRunSync().nonEmpty)
    assert(policyDAO.listAccessPolicies(childResource, samRequestContext).unsafeRunSync().nonEmpty)

    // try to delete the parent resource and then validate the error
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.deleteResource(parentResource, samRequestContext))
    }

    exception.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)

    // make sure the parent still exists
    assert(policyDAO.listAccessPolicies(parentResource, samRequestContext).unsafeRunSync().nonEmpty)
  }

  "validatePolicy" should "succeed with a correct policy" in {
    val emailToMaybeSubject = Map(dummyUserInfo.userEmail -> Option(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]))
    val policy = service.ValidatableAccessPolicy(AccessPolicyName("a"), emailToMaybeSubject, Set(ownerRoleName), Set(ResourceAction("alter_policies")), Set())
    runAndWait(service.validatePolicy(defaultResourceType, policy)) shouldBe empty
  }

  "validatePolicy" should "fail with an incorrect policy" in {
    val emailToMaybeSubject = Map(dummyUserInfo.userEmail -> Option(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]))
    val policy = service.ValidatableAccessPolicy(AccessPolicyName("a"), emailToMaybeSubject, Set(ResourceRoleName("bad_name")), Set(ResourceAction("bad_action")), Set())
    val maybeErrorReport = runAndWait(service.validatePolicy(defaultResourceType, policy))
    maybeErrorReport.value.message should include("invalid policy")
  }

  "validateRoles" should "fail if role is not in listed roles" in {
    val maybeErrorReport =
      service.validateRoles(defaultResourceType, Set(ResourceRoleName("asdf")))
    maybeErrorReport.value.message should include("invalid role")
  }

  "validateRoles" should "succeed with role included in listed roles" in {
    service.validateRoles(defaultResourceType, Set(ownerRoleName)) shouldBe empty
  }

  "validateActions" should "fail if action is not in listed actions" in {
    val maybeErrorReport =
      service.validateActions(defaultResourceType, Set(ResourceAction("asdf")))
    maybeErrorReport shouldBe defined
    maybeErrorReport.value.message should include("invalid action")
  }

  "validateActions" should "succeed with action included in listed actions" in {
    service.validateActions(defaultResourceType, Set(ResourceAction("alter_policies"))) shouldBe empty
  }

  "add/remove SubjectToPolicy" should "add/remove subject and tolerate prior (non)existence" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    val policyName = AccessPolicyName(defaultResourceType.ownerRoleName.value)
    val otherUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("otheruserid"), WorkbenchEmail("otheruser@company.com"), 0)

    dirDAO.createUser(WorkbenchUser(otherUserInfo.userId, None, otherUserInfo.userEmail, None), samRequestContext).unsafeRunSync()

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    // assert baseline
    service.policyEvaluatorService.listUserResources(defaultResourceType.name, otherUserInfo.userId, samRequestContext).unsafeRunSync() shouldBe empty

    runAndWait(service.addSubjectToPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId, samRequestContext))
    service.policyEvaluatorService.listUserResources(defaultResourceType.name, otherUserInfo.userId, samRequestContext).unsafeRunSync() should contain theSameElementsAs
      Set(UserResourcesResponse(resource.resourceId, RolesAndActions.fromRoles(Set(defaultResourceType.ownerRoleName)), RolesAndActions.empty, RolesAndActions.empty, Set.empty, Set.empty))

    // add a second time to make sure no exception is thrown
    runAndWait(service.addSubjectToPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId, samRequestContext))


    runAndWait(service.removeSubjectFromPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId, samRequestContext))
    service.policyEvaluatorService.listUserResources(defaultResourceType.name, otherUserInfo.userId, samRequestContext).unsafeRunSync() shouldBe empty

    // remove a second time to make sure no exception is thrown
    runAndWait(service.removeSubjectFromPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId, samRequestContext))
  }

  "addSubjectToPolicy" should "call CloudExtensions.onGroupUpdate when member added" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mock[AccessPolicyDAO](RETURNS_SMART_NULLS),
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val member = WorkbenchUserId("testU")

    // return value true at the end indicates group changed
    when(mockDirectoryDAO.addGroupMember(ArgumentMatchers.eq(policyId), ArgumentMatchers.eq(member), any[SamRequestContext])).thenReturn(IO.pure(true))
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))
    runAndWait(resourceService.addSubjectToPolicy(policyId, member, samRequestContext))

    verify(mockCloudExtensions, Mockito.timeout(500)).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  it should "not call CloudExtensions.onGroupUpdate when member added but is already there" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mock[AccessPolicyDAO](RETURNS_SMART_NULLS),
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val member = WorkbenchUserId("testU")

    // return value false at the end indicates group did not change
    when(mockDirectoryDAO.addGroupMember(ArgumentMatchers.eq(policyId), ArgumentMatchers.eq(member), any[SamRequestContext])).thenReturn(IO.pure(false))
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))
    runAndWait(resourceService.addSubjectToPolicy(policyId, member, samRequestContext))

    verify(mockCloudExtensions, Mockito.after(500).never).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  "removeSubjectFromPolicy" should "call CloudExtensions.onGroupUpdate when member removed" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mock[AccessPolicyDAO](RETURNS_SMART_NULLS),
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val member = WorkbenchUserId("testU")

    // return value true at the end indicates group changed
    when(mockDirectoryDAO.removeGroupMember(ArgumentMatchers.eq(policyId), ArgumentMatchers.eq(member), any[SamRequestContext])).thenReturn(IO.pure(true))
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))
    runAndWait(resourceService.removeSubjectFromPolicy(policyId, member, samRequestContext))

    verify(mockCloudExtensions, Mockito.timeout(1000)).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  it should "not call CloudExtensions.onGroupUpdate when member removed but wasn't there to start with" in {
    val mockCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
    val mockDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val resourceService = new ResourceService(Map.empty,
      mock[PolicyEvaluatorService](RETURNS_SMART_NULLS),
      mock[AccessPolicyDAO](RETURNS_SMART_NULLS),
      mockDirectoryDAO,
      mockCloudExtensions,
      "")

    val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("testR")), AccessPolicyName("testA"))
    val member = WorkbenchUserId("testU")

    // return value false at the end indicates group did not change
    when(mockDirectoryDAO.removeGroupMember(ArgumentMatchers.eq(policyId), ArgumentMatchers.eq(member), any[SamRequestContext])).thenReturn(IO.pure(false))
    when(mockCloudExtensions.onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])).thenReturn(Future.successful(()))
    runAndWait(resourceService.removeSubjectFromPolicy(policyId, member, samRequestContext))

    verify(mockCloudExtensions, Mockito.after(500).never).onGroupUpdate(ArgumentMatchers.eq(Seq(policyId)), any[SamRequestContext])
  }

  "initResourceTypes" should "do the happy path" in {
    val adminResType = ResourceType(SamResourceTypes.resourceTypeAdminName,
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ownerRoleName, Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ownerRoleName)

    val service = new ResourceService(Map(adminResType.name -> adminResType, defaultResourceType.name -> defaultResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    val init = service.initResourceTypes().unsafeRunSync()
    init should contain theSameElementsAs(Set(adminResType, defaultResourceType))

    // assert a resource was not created for SamResourceTypes.resourceTypeAdmin
    policyDAO.listAccessPolicies(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(SamResourceTypes.resourceTypeAdminName.value)), samRequestContext).unsafeRunSync() should equal(LazyList.empty)

    // assert a resource was created for defaultResourceType
    val resourceAndPolicyName = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(adminResType.name, ResourceId(defaultResourceType.name.value)), AccessPolicyName("owner"))
    val policy = policyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).unsafeRunSync()
    policy.map(_.copy(email = WorkbenchEmail(""))) should equal(Some(AccessPolicy(resourceAndPolicyName, Set.empty, WorkbenchEmail(""), Set(ownerRoleName), Set.empty, Set.empty, public = false)))

    // add a user to the policy and verify
    policyDAO.overwritePolicy(policy.get.copy(members = Set(dummyUserInfo.userId)), samRequestContext).unsafeRunSync()
    val policy2 = policyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).unsafeRunSync()
    policy2.map(_.copy(email = WorkbenchEmail(""))) should equal(Some(AccessPolicy(resourceAndPolicyName, Set(dummyUserInfo.userId), WorkbenchEmail(""), Set(ownerRoleName), Set.empty, Set.empty, public = false)))

    // call it again to ensure it does not fail
    service.initResourceTypes().unsafeRunSync()

    // verify the policy has not changed
    val policy3 = policyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).unsafeRunSync()
    policy3.map(_.copy(email = WorkbenchEmail(""))) should equal(Some(AccessPolicy(resourceAndPolicyName, Set(dummyUserInfo.userId), WorkbenchEmail(""), Set(ownerRoleName), Set.empty, Set.empty, public = false)))
  }

  it should "fail if resourceTypeAdmin not defined" in {
    val service = new ResourceService(Map(defaultResourceType.name -> defaultResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    intercept[WorkbenchException] {
      service.initResourceTypes().unsafeRunSync()
    }
  }

  it should "update effective resource tables if descendant permissions are removed" in {
    val adminResType = ResourceType(SamResourceTypes.resourceTypeAdminName,
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ownerRoleName, Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ownerRoleName)

    val parentResourceId = FullyQualifiedResourceId(parentResourceType.name, ResourceId(UUID.randomUUID().toString))
    val parentOwnerPolicy = FullyQualifiedPolicyId(parentResourceId, AccessPolicyName("owner"))
    val childResourceId = FullyQualifiedResourceId(childResourceType.name, ResourceId(UUID.randomUUID().toString))

    val service = new ResourceService(Map(adminResType.name -> adminResType, parentResourceType.name -> parentResourceType, childResourceType.name -> childResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    val init = service.initResourceTypes().unsafeRunSync()
    init should contain theSameElementsAs(Set(adminResType, parentResourceType, childResourceType))

    val parentResource = runAndWait(service.createResource(parentResourceType, parentResourceId.resourceId, dummyUserInfo, samRequestContext))
    val childResource = runAndWait(service.createResource(childResourceType, childResourceId.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext))

    val user1 = UserIdInfo(WorkbenchUserId("user1"), WorkbenchEmail("user1@fake.com"), None)
    dirDAO.createUser(WorkbenchUser(user1.userSubjectId, None, user1.userEmail, None), samRequestContext).unsafeRunSync()

    runAndWait(service.addSubjectToPolicy(parentOwnerPolicy, user1.userSubjectId, samRequestContext))
    runAndWait(policyEvaluatorService.hasPermission(childResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
    runAndWait(policyEvaluatorService.hasPermission(parentResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true


    val overriddenParentResourceType = parentResourceType.copy(roles = Set(
      parentResourceTypeOwnerRole.copy(descendantRoles = Map.empty),
      parentResourceTypeOtherRole
    ))
    val newService = new ResourceService(Map(adminResType.name -> adminResType, parentResourceType.name -> overriddenParentResourceType, childResourceType.name -> childResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
    val newInit = newService.initResourceTypes().unsafeRunSync()
    newInit should contain theSameElementsAs(Set(adminResType, overriddenParentResourceType, childResourceType))

    runAndWait(policyEvaluatorService.hasPermission(childResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe false
    runAndWait(policyEvaluatorService.hasPermission(parentResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
  }

  it should "update effective resource tables if descendant permissions are added" in {
    val adminResType = ResourceType(SamResourceTypes.resourceTypeAdminName,
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ownerRoleName, Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ownerRoleName)

    val parentResourceId = FullyQualifiedResourceId(parentResourceType.name, ResourceId(UUID.randomUUID().toString))
    val parentOwnerPolicy = FullyQualifiedPolicyId(parentResourceId, AccessPolicyName("owner"))
    val childResourceId = FullyQualifiedResourceId(childResourceType.name, ResourceId(UUID.randomUUID().toString))

    val overriddenParentResourceType = parentResourceType.copy(roles = Set(
      parentResourceTypeOwnerRole.copy(descendantRoles = Map.empty),
      parentResourceTypeOtherRole
    ))
    val newService = new ResourceService(Map(adminResType.name -> adminResType, parentResourceType.name -> overriddenParentResourceType, childResourceType.name -> childResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
    val newInit = newService.initResourceTypes().unsafeRunSync()
    newInit should contain theSameElementsAs(Set(adminResType, overriddenParentResourceType, childResourceType))


    val parentResource = runAndWait(newService.createResource(parentResourceType, parentResourceId.resourceId, dummyUserInfo, samRequestContext))
    val childResource = runAndWait(newService.createResource(childResourceType, childResourceId.resourceId, dummyUserInfo, samRequestContext))

    val user1 = UserIdInfo(WorkbenchUserId("user1"), WorkbenchEmail("user1@fake.com"), None)
    dirDAO.createUser(WorkbenchUser(user1.userSubjectId, None, user1.userEmail, None), samRequestContext).unsafeRunSync()

    runAndWait(newService.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext))
    runAndWait(newService.addSubjectToPolicy(parentOwnerPolicy, user1.userSubjectId, samRequestContext))

    runAndWait(policyEvaluatorService.hasPermission(childResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe false

    val service = new ResourceService(Map(adminResType.name -> adminResType, parentResourceType.name -> parentResourceType, childResourceType.name -> childResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    val init = service.initResourceTypes().unsafeRunSync()
    init should contain theSameElementsAs(Set(adminResType, parentResourceType, childResourceType))

    runAndWait(policyEvaluatorService.hasPermission(childResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
  }

  it should "leave other effective resource relationships intact if descendant permissions are added" in {
    val adminResType = ResourceType(SamResourceTypes.resourceTypeAdminName,
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ownerRoleName, Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ownerRoleName)

    val parentResourceId = FullyQualifiedResourceId(parentResourceType.name, ResourceId(UUID.randomUUID().toString))
    val otherParentResourceId = FullyQualifiedResourceId(otherParentResourceType.name, ResourceId(UUID.randomUUID().toString))
    val otherParentOwnerPolicy = FullyQualifiedPolicyId(otherParentResourceId, AccessPolicyName("owner"))
    val childResourceId = FullyQualifiedResourceId(childResourceType.name, ResourceId(UUID.randomUUID().toString))
    val otherChildResourceId = FullyQualifiedResourceId(childResourceType.name, ResourceId(UUID.randomUUID().toString))

    val service = new ResourceService(Map(adminResType.name -> adminResType, parentResourceType.name -> parentResourceType, childResourceType.name -> childResourceType, otherParentResourceType.name -> otherParentResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    val init = service.initResourceTypes().unsafeRunSync()
    init should contain theSameElementsAs(Set(adminResType, parentResourceType, childResourceType, otherParentResourceType))

    val parentResource = runAndWait(service.createResource(parentResourceType, parentResourceId.resourceId, dummyUserInfo, samRequestContext))
    val childResource = runAndWait(service.createResource(childResourceType, childResourceId.resourceId, dummyUserInfo, samRequestContext))
    val otherParentResource = runAndWait(service.createResource(otherParentResourceType, otherParentResourceId.resourceId, dummyUserInfo, samRequestContext))
    val otherChildResource = runAndWait(service.createResource(childResourceType, otherChildResourceId.resourceId, dummyUserInfo, samRequestContext))
    runAndWait(service.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext))
    runAndWait(service.setResourceParent(otherChildResource.fullyQualifiedId, otherParentResource.fullyQualifiedId, samRequestContext))

    val user1 = UserIdInfo(WorkbenchUserId("user1"), WorkbenchEmail("user1@fake.com"), None)
    dirDAO.createUser(WorkbenchUser(user1.userSubjectId, None, user1.userEmail, None), samRequestContext).unsafeRunSync()

    runAndWait(service.addSubjectToPolicy(otherParentOwnerPolicy, user1.userSubjectId, samRequestContext))
    runAndWait(policyEvaluatorService.hasPermission(otherChildResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
    runAndWait(policyEvaluatorService.hasPermission(otherParentResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
    runAndWait(policyEvaluatorService.hasPermission(childResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe false


    val overriddenParentResourceType = parentResourceType.copy(roles = Set(
      parentResourceTypeOwnerRole.copy(descendantRoles = Map.empty),
      parentResourceTypeOtherRole
    ))
    val newService = new ResourceService(Map(adminResType.name -> adminResType, parentResourceType.name -> overriddenParentResourceType, childResourceType.name -> childResourceType, otherParentResourceType.name -> otherParentResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
    val newInit = newService.initResourceTypes().unsafeRunSync()
    newInit should contain theSameElementsAs(Set(adminResType, overriddenParentResourceType, childResourceType, otherParentResourceType))

    runAndWait(policyEvaluatorService.hasPermission(otherChildResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
    runAndWait(policyEvaluatorService.hasPermission(otherParentResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe true
    runAndWait(policyEvaluatorService.hasPermission(childResource.fullyQualifiedId, ResourceAction("view"), user1.userSubjectId, samRequestContext)) shouldBe false
  }

  "listAllFlattenedResourceUsers" should "return a flattened list of all of the users in any of a resource's policies" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId(UUID.randomUUID().toString))

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo, samRequestContext))

    val dummyUserIdInfo = dirDAO.loadUser(dummyUserInfo.userId, samRequestContext).unsafeRunSync().map { user => UserIdInfo(user.id, user.email, user.googleSubjectId) }.get
    val user1 = UserIdInfo(WorkbenchUserId("user1"), WorkbenchEmail("user1@fake.com"), None)
    val user2 = UserIdInfo(WorkbenchUserId("user2"), WorkbenchEmail("user2@fake.com"), None)
    val user3 = UserIdInfo(WorkbenchUserId("user3"), WorkbenchEmail("user3@fake.com"), None)
    val user4 = UserIdInfo(WorkbenchUserId("user4"), WorkbenchEmail("user4@fake.com"), None)
    val user5 = UserIdInfo(WorkbenchUserId("user5"), WorkbenchEmail("user5@fake.com"), None)
    val user6 = UserIdInfo(WorkbenchUserId("user6"), WorkbenchEmail("user6@fake.com"), None)

    dirDAO.createUser(WorkbenchUser(user1.userSubjectId, None, user1.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user2.userSubjectId, None, user2.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user3.userSubjectId, None, user3.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user4.userSubjectId, None, user4.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user5.userSubjectId, None, user5.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user6.userSubjectId, None, user6.userEmail, None), samRequestContext).unsafeRunSync()

    val ownerPolicy = FullyQualifiedPolicyId(resource, AccessPolicyName("owner"))
    runAndWait(service.addSubjectToPolicy(ownerPolicy, user1.userSubjectId, samRequestContext))
    runAndWait(service.addSubjectToPolicy(ownerPolicy, user2.userSubjectId, samRequestContext))

    val group1 = BasicWorkbenchGroup(WorkbenchGroupName("group1"), Set(user3.userSubjectId, user4.userSubjectId), WorkbenchEmail("group1@fake.com"))
    val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user6.userSubjectId), WorkbenchEmail("subgroup@fake.com"))
    val group2 = BasicWorkbenchGroup(WorkbenchGroupName("group2"), Set(user5.userSubjectId, subGroup.id), WorkbenchEmail("group2@fake.com"))

    dirDAO.createGroup(group1, samRequestContext = samRequestContext).unsafeRunSync()
    dirDAO.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
    dirDAO.createGroup(group2, samRequestContext = samRequestContext).unsafeRunSync()
    runAndWait(service.createPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("reader")), Set(group1.id, group2.id), Set.empty, Set.empty, Set.empty, samRequestContext))

    service.listAllFlattenedResourceUsers(resource, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(dummyUserIdInfo, user1, user2, user3, user4, user5, user6)
  }

  it should "return a flattened list of all of the users in any of a resource's policies even if the users are in a managed group" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId(UUID.randomUUID().toString))
    val managedGroupName = "foo"
    val resourceOwnerUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("user1"), WorkbenchEmail("user1@company.com"), 0)
    val managedGroupOwnerUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("user2"), WorkbenchEmail("user2@company.com"), 0)

    dirDAO.createUser(WorkbenchUser(resourceOwnerUserInfo.userId, TestSupport.genGoogleSubjectId(), resourceOwnerUserInfo.userEmail, Some(TestSupport.genAzureB2CId())), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(managedGroupOwnerUserInfo.userId, TestSupport.genGoogleSubjectId(), managedGroupOwnerUserInfo.userEmail, Some(TestSupport.genAzureB2CId())), samRequestContext).unsafeRunSync()

    val resourceOwner = dirDAO.loadUser(resourceOwnerUserInfo.userId, samRequestContext).unsafeRunSync().map { user => UserIdInfo(user.id, user.email, user.googleSubjectId) }.get
    val managedGroupOwner = dirDAO.loadUser(managedGroupOwnerUserInfo.userId, samRequestContext).unsafeRunSync().map { user => UserIdInfo(user.id, user.email, user.googleSubjectId) }.get

    val directPolicyMember = UserIdInfo(WorkbenchUserId("directMember"), WorkbenchEmail("directMember@fake.com"), None)
    val user3 = UserIdInfo(WorkbenchUserId("user3"), WorkbenchEmail("user3@fake.com"), None)
    val user4 = UserIdInfo(WorkbenchUserId("user4"), WorkbenchEmail("user4@fake.com"), None)

    dirDAO.createUser(WorkbenchUser(directPolicyMember.userSubjectId, None, directPolicyMember.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user3.userSubjectId, None, user3.userEmail, None), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user4.userSubjectId, None, user4.userEmail, None), samRequestContext).unsafeRunSync()

    service.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    service.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, resourceOwnerUserInfo, samRequestContext))
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), managedGroupOwnerUserInfo, samRequestContext = samRequestContext))
    runAndWait(service.createPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("can-catalog")), Set(directPolicyMember.userSubjectId, WorkbenchGroupName(managedGroupName)), Set(ResourceRoleName("other")), Set.empty, Set.empty, samRequestContext))

    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupName), ManagedGroupService.memberPolicyName, user3.userSubjectId, samRequestContext))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupName), ManagedGroupService.memberPolicyName, user4.userSubjectId, samRequestContext))

    service.listAllFlattenedResourceUsers(resource, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(resourceOwner, managedGroupOwner, directPolicyMember, user3, user4)
  }

  "loadAccessPolicyWithEmails" should "get emails for users, groups and policies" in {
    val testResult = for {
      _ <- service.createResourceType(defaultResourceType, samRequestContext)

      testGroup <- dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("mygroup"), Set.empty, WorkbenchEmail("group@a.com")), samRequestContext = samRequestContext)

      res1 <- service.createResource(defaultResourceType, ResourceId("resource1"), dummyUserInfo, samRequestContext)
      testPolicy <- service.listResourcePolicies(res1.fullyQualifiedId, samRequestContext).map(_.head)

      res2 <- service.createResource(defaultResourceType, ResourceId("resource2"), dummyUserInfo, samRequestContext)

      newPolicy <- policyDAO.createPolicy(AccessPolicy(
        FullyQualifiedPolicyId(res2.fullyQualifiedId, AccessPolicyName("foo")), Set(testGroup.id, dummyUserInfo.userId, FullyQualifiedPolicyId(res1.fullyQualifiedId, testPolicy.policyName)), WorkbenchEmail("a@b.c"), Set.empty, Set.empty, Set.empty, public = false), samRequestContext)

      membership <- policyDAO.loadPolicyMembership(newPolicy.id, samRequestContext)
    } yield {
      membership.value.memberEmails should contain theSameElementsAs Set(
        testGroup.email,
        dummyUserInfo.userEmail,
        testPolicy.email
      )
    }

    implicit val patienceConfig = PatienceConfig(5.seconds)
    testResult.unsafeRunSync()
  }

  "setResourceParent" should "throw if the child resource has an auth domain" in {
    val childAccessPolicies = Map (
      AccessPolicyName("constrainable") -> constrainablePolicyMembership
    )

    val testResult = for {
      _ <- service.createResourceType(constrainableResourceType, samRequestContext)
      _ <- service.createResourceType(managedGroupResourceType, samRequestContext)

      _ <- managedGroupService.createManagedGroup(ResourceId("authDomain"), dummyUserInfo, samRequestContext = samRequestContext)
      childResource <- service.createResource(constrainableResourceType, ResourceId("child"), childAccessPolicies, Set(WorkbenchGroupName("authDomain")), None, dummyUserInfo.userId, samRequestContext)
      parentResource <- service.createResource(constrainableResourceType, ResourceId("parent"), dummyUserInfo, samRequestContext)
      _ <- service.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
    } yield ()

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      testResult.unsafeRunSync()
    }

    exception.errorReport.statusCode shouldBe Option(StatusCodes.BadRequest)
  }

  "deletePolicy" should "delete the policy" in {
    val testResult = for {
      _ <- service.createResourceType(defaultResourceType, samRequestContext)
      resource <- service.createResource(defaultResourceType, ResourceId("resource"), dummyUserInfo, samRequestContext)

      policyId = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))

      _ <- service.deletePolicy(policyId, samRequestContext)
      loadedPolicy <- policyDAO.loadPolicy(policyId, samRequestContext)
    } yield loadedPolicy

    testResult.unsafeRunSync() shouldBe None
  }

  "validateDescendantPermissions" should "allow good descendant permissions" in {
    service.validateDescendantPermissions(Set(
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set.empty, Set(defaultResourceType.ownerRoleName)),
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set(defaultResourceTypeActions.head), Set.empty),
    )).unsafeRunSync() shouldBe empty
  }

  it should "catch non-existent descendant resource type" in {
    service.validateDescendantPermissions(Set(
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set.empty, Set(defaultResourceType.ownerRoleName)),
      AccessPolicyDescendantPermissions(ResourceTypeName("I don't exist"), Set.empty, Set.empty)
    )).unsafeRunSync() should contain theSameElementsAs Set(ErrorReport(sam.errorReportSource.source,"Descendant resource type I don't exist does not exist.",None,List(),List(),None))
  }

  it should "catch non-existent role and action" in {
    service.validateDescendantPermissions(Set(
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set.empty, Set(defaultResourceType.ownerRoleName)),
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set.empty, Set(ResourceRoleName("I don't exist"))),
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set(ResourceAction("I don't exist either")), Set.empty),
      AccessPolicyDescendantPermissions(defaultResourceType.name, Set(ResourceAction("I don't exist either")), Set(ResourceRoleName("I don't exist"))),
    )).unsafeRunSync().size shouldBe 4
  }
}
