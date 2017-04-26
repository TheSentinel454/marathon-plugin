package com.mesosphere.velocity.marathon;

import hudson.model.Result;
import net.sf.json.JSONObject;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MarathonStepTest {
    @Rule
    public JenkinsRule j    = new JenkinsRule();
    @Rule
    public TestName    name = new TestName();

    /**
     * An HTTP Server to receive requests from the plugin.
     */
    private MockWebServer httpServer;

    @Before
    public void setUp() throws IOException {
        httpServer = new MockWebServer();
        httpServer.start();
    }

    @After
    public void tearDown() throws IOException {
        httpServer.shutdown();
        httpServer = null;
    }

    /**
     * Test using "id" and not receiving a deprecation warning message.
     *
     * @throws Exception in case something unexpected happens
     */
    @Test
    public void testStepFail() throws Exception {
        TestUtils.enqueueFailureResponse(httpServer, 404);
        final WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, name.getMethodName());

        job.setDefinition(new CpsFlowDefinition(generateSimpleScript(null, name.getMethodName()), true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(1).get());
        j.assertLogNotContains("DEPRECATION WARNING", run);
        assertEquals("Only 1 request should be made", 1, httpServer.getRequestCount());
    }

    /**
     * Test that using "appid" instead of "id" shows a deprecation warning message.
     *
     * @throws Exception in case something unexpected happens
     */
    @Test
    public void testStepAppIdDeprecationMessage() throws Exception {
        TestUtils.enqueueFailureResponse(httpServer, 404);
        final WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, name.getMethodName());

        final String groovyScript = "node { " +
                "writeFile(encoding: 'utf-8', file: 'marathon.json', text: '{\"id\": \"testing\", \"cmd\": \"sleep 60\"}');\n" +
                "marathon(appid: 'testStepAppIdDeprecationMessage', url: '" + TestUtils.getHttpAddresss(httpServer) + "'); " +
                "}";

        job.setDefinition(new CpsFlowDefinition(groovyScript, true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(1).get());
        j.assertLogContains("DEPRECATION WARNING", run);
        assertEquals("Only 1 request should be made", 1, httpServer.getRequestCount());
    }

    /**
     * Test that 409 triggers retries.
     *
     * @throws Exception if something goes wrong
     */
    @Test
    public void testMaxRetries() throws Exception {
        TestUtils.enqueueFailureResponse(httpServer, 409);
        TestUtils.enqueueFailureResponse(httpServer, 409);
        TestUtils.enqueueFailureResponse(httpServer, 409);

        final WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, name.getMethodName());

        job.setDefinition(new CpsFlowDefinition(generateSimpleScript(), true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(1).get());
        j.assertLogContains("Client Error", run);
        j.assertLogContains("http status: 409", run);
        assertEquals("Only 1 request should be made", 1, httpServer.getRequestCount());
    }

    /**
     * Test that when "marathon.json" is not present the build is failed
     * and no requests are made to the configured marathon instance.
     *
     * @throws Exception when errors occur.
     */
    @Test
    public void testRecorderNoFile() throws Exception {
        TestUtils.enqueueFailureResponse(httpServer, 400);

        final WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "recordernofile");
        job.setDefinition(new CpsFlowDefinition("node {" +
                "marathon(id: '" + name.getMethodName() +
                "', url: '" + TestUtils.getHttpAddresss(httpServer) + "'); " +
                "}"));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(1).get());
        j.assertLogContains("Could not find file 'marathon.json'", run);
        assertEquals("No requests were made", 0, httpServer.getRequestCount());
    }

    /**
     * Test a basic successful scenario. The marathin instance will return
     * a 200 OK.
     *
     * @throws Exception if things go awry
     */
    @Test
    public void testRecorderPass() throws Exception {
        final String      payload  = "{\"id\":\"myapp\"}";
        final WorkflowJob job      = j.jenkins.createProject(WorkflowJob.class, name.getMethodName());
        final String      response = "{\"version\": \"one\", \"deploymentId\": \"myapp\"}";
        TestUtils.enqueueJsonResponse(httpServer, response);

        job.setDefinition(new CpsFlowDefinition(generateSimpleScript(payload, null), true));
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(1).get());
        assertEquals("Only 1 request should be made", 1, httpServer.getRequestCount());

        RecordedRequest request        = httpServer.takeRequest();
        final String    requestPayload = request.getBody().readUtf8();
        JSONObject      json           = JSONObject.fromObject(requestPayload);
        assertEquals("Id was not set correctly", "myapp", json.getString("id"));
    }

    /**
     * Test various marathon fields and confirm what was in marathon.json is what
     * was received by the marathon instance.
     *
     * @throws Exception when problems happen
     */
    @Test
    public void testMarathonAllFields() throws Exception {
        final String payload = "{\n" +
                "  \"id\": \"test-app\",\n" +
                "  \"container\": {\n" +
                "    \"type\": \"DOCKER\",\n" +
                "    \"docker\": {\n" +
                "      \"image\": \"mesosphere/test-app:latest\",\n" +
                "      \"forcePullImage\": true,\n" +
                "      \"network\": \"BRIDGE\",\n" +
                "      \"portMappings\": [\n" +
                "        {\n" +
                "          \"hostPort\": 80,\n" +
                "          \"containerPort\": 80,\n" +
                "          \"protocol\": \"tcp\"\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  },\n" +
                "  \"acceptedResourceRoles\": [\n" +
                "    \"agent_public\"\n" +
                "  ],\n" +
                "  \"labels\": {\n" +
                "    \"lastChangedBy\": \"test@example.com\"\n" +
                "  },\n" +
                "  \"uris\": [ \"http://www.example.com/file\" ],\n" +
                "  \"instances\": 1,\n" +
                "  \"cpus\": 0.1,\n" +
                "  \"mem\": 128,\n" +
                "  \"healthChecks\": [\n" +
                "    {\n" +
                "      \"protocol\": \"TCP\",\n" +
                "      \"gracePeriodSeconds\": 600,\n" +
                "      \"intervalSeconds\": 30,\n" +
                "      \"portIndex\": 0,\n" +
                "      \"timeoutSeconds\": 10,\n" +
                "      \"maxConsecutiveFailures\": 2\n" +
                "    }\n" +
                "  ],\n" +
                "  \"upgradeStrategy\": {\n" +
                "        \"minimumHealthCapacity\": 0\n" +
                "  },\n" +
                "  \"backoffSeconds\": 1,\n" +
                "  \"backoffFactor\": 1.15,\n" +
                "  \"maxLaunchDelaySeconds\": 3600\n" +
                "}";
        final String responseStr = "{\"version\": \"one\", \"deploymentId\": \"someid-here\"}";
        TestUtils.enqueueJsonResponse(httpServer, responseStr);

        final WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, name.getMethodName());
        job.setDefinition(new CpsFlowDefinition(generateSimpleScript(payload, null), true));
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(1).get());
        assertEquals("Only 1 request should be made", 1, httpServer.getRequestCount());

        RecordedRequest request        = httpServer.takeRequest();
        final String    requestPayload = request.getBody().readUtf8();
        JSONObject      json           = JSONObject.fromObject(requestPayload);
        assertEquals("Id was not set correctly", "test-app", json.getString("id"));

        final JSONObject jsonPayload = JSONObject.fromObject(payload);

        // verify that each root field is present in the received request
        for (Object key : jsonPayload.keySet()) {
            final String keyStr = (String) key;
            assertTrue(String.format("JSON is missing field: %s", keyStr), json.containsKey(keyStr));
        }
    }

    /**
     * Test that the URL is properly put through the replace macro and able to be populated with
     * Jenkins variables.
     *
     * @throws Exception in some special instances
     */
    @Test
    public void testURLMacro() throws Exception {
        final String responseStr = "{\"version\": \"one\", \"deploymentId\": \"someid-here\"}";
        TestUtils.enqueueJsonResponse(httpServer, responseStr);

        final WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, name.getMethodName());

        final String groovyScript = "node { " +
                "writeFile(encoding: 'utf-8', file: 'marathon.json', text: '{\"id\": \"myapp\", \"cmd\": \"sleep 60\"}');\n" +
                "marathon(url: '" + TestUtils.getHttpAddresss(httpServer) + "${BUILD_NUMBER}'); " +
                "}";

        job.setDefinition(new CpsFlowDefinition(groovyScript, true));
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(1).get());

        assertEquals("Only 1 request should be made", 1, httpServer.getRequestCount());
        RecordedRequest request     = httpServer.takeRequest();
        String          requestPath = request.getPath();
        if (requestPath.contains("?")) {
            requestPath = requestPath.substring(0, requestPath.indexOf("?"));
        }

        assertEquals("App URL should have build number",
                "/" + String.valueOf(run.getNumber()) + "/v2/apps/myapp",
                requestPath);
    }

    /**
     * See {@link #generateSimpleScript(String, String)} for details.
     *
     * @return pipeline groovy script
     */
    private String generateSimpleScript() {
        return generateSimpleScript(null, null);
    }

    /**
     * Helper method to generate the groovy script for pipeline jobs.
     *
     * @param fileContents JSON contents for marathon.json file (optional)
     * @param id           marathon id for application (optional)
     * @return pipeline groovy script
     */
    private String generateSimpleScript(final String fileContents, final String id) {
        final String nodeScript = "node { \n" +
                "writeFile(encoding: 'utf-8', file: 'marathon.json', text: \"\"\"%s\"\"\");\n" +
                "marathon(%s url: '%s');\n" +
                "}";
        return String.format(nodeScript,
                fileContents == null ? "{\"id\": \"testing\", \"cmd\": \"sleep 60\"}" : fileContents,
                id == null ? "" : "id: '" + id + "', ",
                TestUtils.getHttpAddresss(httpServer));
    }
}
