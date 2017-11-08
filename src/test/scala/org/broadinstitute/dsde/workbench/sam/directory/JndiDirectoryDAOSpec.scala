package org.broadinstitute.dsde.workbench.sam.directory

import java.util.UUID

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 5/30/17.
  */
class JndiDirectoryDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dao = new JndiDirectoryDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig)

  override protected def beforeAll(): Unit = {
    runAndWait(schemaDao.init())
  }


  override protected def afterAll(): Unit = {
    runAndWait(schemaDao.clearDatabase())
  }

  "JndiGroupDirectoryDAO" should "create, read, delete groups" in {
    val groupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val group = BasicWorkbenchGroup(groupName, Set.empty, WorkbenchGroupEmail("john@doe.org"))

    assertResult(None) {
      runAndWait(dao.loadGroup(group.id))
    }

    assertResult(group) {
      runAndWait(dao.createGroup(group))
    }

    assertResult(Some(group)) {
      runAndWait(dao.loadGroup(group.id))
    }

    runAndWait(dao.deleteGroup(group.id))

    assertResult(None) {
      runAndWait(dao.loadGroup(group.id))
    }
  }

  it should "create, read, delete users" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(Some(user)) {
      runAndWait(dao.loadUser(user.id))
    }

    runAndWait(dao.deleteUser(user.id))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }
  }

  it should "create, read, delete pet service accounts" in {
    val serviceAccountUniqueId = WorkbenchUserServiceAccountSubjectId(UUID.randomUUID().toString)
    val serviceAccount = WorkbenchUserServiceAccount(serviceAccountUniqueId, WorkbenchUserServiceAccountEmail("foo@bar.com"), WorkbenchUserServiceAccountDisplayName(""))

    assertResult(None) {
      runAndWait(dao.loadPetServiceAccount(serviceAccount.subjectId))
    }

    assertResult(serviceAccount) {
      runAndWait(dao.createPetServiceAccount(serviceAccount))
    }

    assertResult(Some(serviceAccount)) {
      runAndWait(dao.loadPetServiceAccount(serviceAccount.subjectId))
    }

    runAndWait(dao.deletePetServiceAccount(serviceAccount.subjectId))

    assertResult(None) {
      runAndWait(dao.loadPetServiceAccount(serviceAccount.subjectId))
    }
  }

  it should "list groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchGroupEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchGroupEmail("g2@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    try {
      assertResult(Set(groupName1, groupName2)) {
        runAndWait(dao.listUsersGroups(userId))
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
    }
  }

  it should "list flattened group users" in {
    val userId1 = WorkbenchUserId(UUID.randomUUID().toString)
    val user1 = WorkbenchUser(userId1, WorkbenchUserEmail("foo@bar.com"))
    val userId2 = WorkbenchUserId(UUID.randomUUID().toString)
    val user2 = WorkbenchUser(userId2, WorkbenchUserEmail("foo@bar.com"))
    val userId3 = WorkbenchUserId(UUID.randomUUID().toString)
    val user3 = WorkbenchUser(userId3, WorkbenchUserEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId1), WorkbenchGroupEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(userId2, groupName1), WorkbenchGroupEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(userId3, groupName2), WorkbenchGroupEmail("g3@example.com"))

    runAndWait(dao.createUser(user1))
    runAndWait(dao.createUser(user2))
    runAndWait(dao.createUser(user3))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))
    runAndWait(dao.createGroup(group3))

    try {
      assertResult(Set(userId1, userId2, userId3)) {
        runAndWait(dao.listFlattenedGroupUsers(groupName3))
      }
    } finally {
      runAndWait(dao.deleteUser(userId1))
      runAndWait(dao.deleteUser(userId2))
      runAndWait(dao.deleteUser(userId3))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName3))
    }
  }

  it should "list group ancestors" in {
    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(), WorkbenchGroupEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchGroupEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(groupName2), WorkbenchGroupEmail("g3@example.com"))

    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))
    runAndWait(dao.createGroup(group3))

    try {
      assertResult(Set(groupName2, groupName3)) {
        runAndWait(dao.listAncestorGroups(groupName1))
      }
    } finally {
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName3))
    }
  }

  it should "handle circular groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchGroupEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchGroupEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(groupName2), WorkbenchGroupEmail("g3@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))
    runAndWait(dao.createGroup(group3))

    runAndWait(dao.addGroupMember(groupName1, groupName3))

    try {
      assertResult(Set(userId)) {
        runAndWait(dao.listFlattenedGroupUsers(groupName3))
      }

      assertResult(Set(groupName1, groupName2, groupName3)) {
        runAndWait(dao.listUsersGroups(userId))
      }

      assertResult(Set(groupName1, groupName2, groupName3)) {
        runAndWait(dao.listAncestorGroups(groupName3))
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName3))
    }
  }

  it should "add/remove groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set.empty, WorkbenchGroupEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchGroupEmail("g2@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    try {

      runAndWait(dao.addGroupMember(groupName1, userId))

      assertResult(Some(group1.copy(members = Set(userId)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.addGroupMember(groupName1, groupName2))

      assertResult(Some(group1.copy(members = Set(userId, groupName2)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.removeGroupMember(groupName1, userId))

      assertResult(Some(group1.copy(members = Set(groupName2)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.removeGroupMember(groupName1, groupName2))

      assertResult(Some(group1)) {
        runAndWait(dao.loadGroup(groupName1))
      }

    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
    }
  }

  it should "associate pet service accounts with users" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))
    val email = WorkbenchUserServiceAccountEmail("myPetSa@gmail.com")

    // create a user
    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(Some(user)) {
      runAndWait(dao.loadUser(user.id))
    }

    // it should initially have no pet service account
    assertResult(None) {
      runAndWait(dao.getPetServiceAccountForUser(userId))
    }

    // add a pet service account
    assertResult(email) {
      runAndWait(dao.addPetServiceAccountToUser(userId, email))
    }

    // get the pet service account
    assertResult(Some(email)) {
      runAndWait(dao.getPetServiceAccountForUser(userId))
    }

    // add the same pet service account, expect an error
    assertThrows[WorkbenchExceptionWithErrorReport] {
      runAndWait(dao.addPetServiceAccountToUser(userId, email))
    }

    // delete the pet service account
    runAndWait(dao.removePetServiceAccountFromUser(userId))

    assertResult(None) {
      runAndWait(dao.getPetServiceAccountForUser(userId))
    }

    // delete the user
    runAndWait(dao.deleteUser(user.id))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }
  }

  it should "get pet service account associated with users" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))
    val email = WorkbenchUserServiceAccountEmail(s"pet-$userId@gmail.com")

    // create a user
    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(Some(user)) {
      runAndWait(dao.loadUser(user.id))
    }

    // it should initially have no pet service account
    assertResult(None) {
      runAndWait(dao.getUserFromPetServiceAccount(email))
    }

    // add a pet service account
    assertResult(email) {
      runAndWait(dao.addPetServiceAccountToUser(userId, email))
    }

    // get the pet service account
    assertResult(Some(user)) {
      runAndWait(dao.getUserFromPetServiceAccount(email))
    }

    // delete the user
    runAndWait(dao.deleteUser(user.id))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }
  }

  it should "handle different kinds of groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchUserEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchGroupEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchGroupEmail("g2@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    val policyDAO = new JndiAccessPolicyDAO(directoryConfig)

    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)

    val policy1 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-a")), Set(userId), WorkbenchGroupEmail("p1@example.com"), Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))

    runAndWait(policyDAO.createResourceType(typeName1))
    runAndWait(policyDAO.createResource(policy1.id.resource))
    runAndWait(policyDAO.createPolicy(policy1))

    assert(runAndWait(dao.isGroupMember(group1.id, userId)))
    assert(!runAndWait(dao.isGroupMember(group2.id, userId)))
    assert(runAndWait(dao.isGroupMember(policy1.id, userId)))
  }
}

}


