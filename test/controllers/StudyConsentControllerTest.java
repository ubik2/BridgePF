package controllers;

import static org.apache.commons.httpclient.HttpStatus.SC_FORBIDDEN;
import static org.apache.commons.httpclient.HttpStatus.SC_OK;
import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.TestConstants.STUDYCONSENT_ACTIVE_URL;
import static org.sagebionetworks.bridge.TestConstants.STUDYCONSENT_URL;
import static org.sagebionetworks.bridge.TestConstants.TIMEOUT;
import static play.test.Helpers.running;
import static play.test.Helpers.testServer;

import javax.annotation.Resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.TestUserAdminHelper;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dynamodb.DynamoStudyConsent1;
import org.sagebionetworks.bridge.dynamodb.DynamoTestUtil;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import play.libs.WS.Response;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class StudyConsentControllerTest {

    private ObjectMapper mapper = new ObjectMapper();

    public StudyConsentControllerTest() {
        mapper.setSerializationInclusion(Include.NON_NULL);
    }

    @Resource
    private TestUserAdminHelper helper;

    @Before
    public void before() {
        helper.createOneUser();
    }

    @After
    public void after() {
        helper.deleteOneUser();
        DynamoTestUtil.clearTable(DynamoStudyConsent1.class, "active", "path", "minAge", "version");
    }

    @Test
    public void test() {
        running(testServer(3333), new TestUtils.FailableRunnable() {

            @Override
            public void testCode() throws Exception {
                // Fields are order independent.
                String consent = "{\"minAge\":17,\"path\":\"fake-path\"}";

                Response addConsentFail = TestUtils.getURL(helper.getUserSessionToken(), STUDYCONSENT_URL)
                                                .post(consent)
                                                .get(TIMEOUT);
                assertEquals("Must be admin to access consent.", SC_FORBIDDEN, addConsentFail.getStatus());

                Response addConsent = TestUtils.getURL(helper.getAdminSessionToken(), STUDYCONSENT_URL)
                                            .post(consent)
                                            .get(TIMEOUT);
                assertEquals("Successfully add consent.", SC_OK, addConsent.getStatus());

                // Get timeout to access this consent later.
                String createdOn = addConsent.asJson().get("createdOn").asText();

                Response setActive = TestUtils
                        .getURL(helper.getAdminSessionToken(), STUDYCONSENT_ACTIVE_URL + "/" + createdOn)
                        .post("")
                        .get(TIMEOUT);
                assertEquals("Successfully set active consent.", SC_OK, setActive.getStatus());

                Response getActive = TestUtils.getURL(helper.getAdminSessionToken(), STUDYCONSENT_ACTIVE_URL)
                                            .get()
                                            .get(TIMEOUT);
                assertEquals("Successfully get active consent.", SC_OK, getActive.getStatus());

                Response getAll = TestUtils.getURL(helper.getAdminSessionToken(), STUDYCONSENT_URL)
                                        .get()
                                        .get(TIMEOUT);
                assertEquals("Successfully get all consents.", SC_OK, getAll.getStatus());

            }
        });
    }

}
