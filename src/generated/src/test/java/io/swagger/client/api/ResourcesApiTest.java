/*
 * Sam
 * Workbench identity and access management. 
 *
 * OpenAPI spec version: 0.1
 * 
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */


package io.swagger.client.api;

import io.swagger.client.ApiException;
import io.swagger.client.model.AccessPolicyMembership;
import io.swagger.client.model.AccessPolicyResponseEntry;
import io.swagger.client.model.ErrorReport;
import io.swagger.client.model.ResourceAndAccessPolicy;
import io.swagger.client.model.ResourceType;
import org.junit.Test;
import org.junit.Ignore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API tests for ResourcesApi
 */
@Ignore
public class ResourcesApiTest {

    private final ResourcesApi api = new ResourcesApi();

    
    /**
     * Create a new resource
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void createResourceTest() throws ApiException {
        String resourceTypeName = null;
        String resourceId = null;
        api.createResource(resourceTypeName, resourceId);

        // TODO: test validations
    }
    
    /**
     * Delete a resource
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void deleteResourceTest() throws ApiException {
        String resourceTypeName = null;
        String resourceId = null;
        api.deleteResource(resourceTypeName, resourceId);

        // TODO: test validations
    }
    
    /**
     * List the policies for a resource
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void listResourcePoliciesTest() throws ApiException {
        String resourceTypeName = null;
        String resourceId = null;
        List<AccessPolicyResponseEntry> response = api.listResourcePolicies(resourceTypeName, resourceId);

        // TODO: test validations
    }
    
    /**
     * Lists available resource types
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void listResourceTypesTest() throws ApiException {
        List<ResourceType> response = api.listResourceTypes();

        // TODO: test validations
    }
    
    /**
     * List resources and policies for this resource for the caller
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void listResourcesAndPoliciesTest() throws ApiException {
        String resourceTypeName = null;
        List<ResourceAndAccessPolicy> response = api.listResourcesAndPolicies(resourceTypeName);

        // TODO: test validations
    }
    
    /**
     * Overwrite a policy on a resource
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void overwritePolicyTest() throws ApiException {
        String resourceTypeName = null;
        String resourceId = null;
        String policyName = null;
        AccessPolicyMembership policyCreate = null;
        List<String> response = api.overwritePolicy(resourceTypeName, resourceId, policyName, policyCreate);

        // TODO: test validations
    }
    
    /**
     * Query if requesting user may perform the action
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void resourceActionTest() throws ApiException {
        String resourceTypeName = null;
        String resourceId = null;
        String action = null;
        Boolean response = api.resourceAction(resourceTypeName, resourceId, action);

        // TODO: test validations
    }
    
    /**
     * Query for the list of roles that the requesting user has on the resource
     *
     * 
     *
     * @throws ApiException
     *          if the Api call fails
     */
    @Test
    public void resourceRolesTest() throws ApiException {
        String resourceTypeName = null;
        String resourceId = null;
        List<String> response = api.resourceRoles(resourceTypeName, resourceId);

        // TODO: test validations
    }
    
}
