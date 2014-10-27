package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.annotation.Resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.TestUserAdminHelper;
import org.sagebionetworks.bridge.TestUserAdminHelper.TestUser;
import org.sagebionetworks.bridge.dao.StudyConsentDao;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.models.ConsentSignature;
import org.sagebionetworks.bridge.models.StudyConsent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.stormpath.sdk.client.Client;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class ConsentServiceImplTest {

    private StudyConsent studyConsent;

    @Resource
    private Client stormpathClient;

    @Resource
    private ConsentService consentService;

    @Resource
    private StudyConsentDao studyConsentDao;

    @Resource
    private UserConsentDao userConsentDao;

    @Resource
    private TestUserAdminHelper helper;
    
    private TestUser testUser;

    @Before
    public void before() {
        testUser = helper.createUser(ConsentServiceImplTest.class);
        studyConsent = studyConsentDao.addConsent(testUser.getStudy().getKey(), "/path/to", testUser.getStudy()
                .getMinAge());
        studyConsentDao.setActive(studyConsent, true);
    }

    @After
    public void after() {
        studyConsentDao.setActive(studyConsent, false);
        studyConsentDao.deleteConsent(testUser.getStudy().getKey(), studyConsent.getCreatedOn());
        helper.deleteUser(testUser);
    }

    @Test
    public void test() {
        ConsentSignature researchConsent = new ConsentSignature("John Smith", "2011-11-11");
        boolean sendEmail = false;

        // Withdrawing and consenting again should return to original state.
        consentService.withdrawConsent(testUser.getUser(), testUser.getStudy());
        consentService.consentToResearch(testUser.getUser(), researchConsent, testUser.getStudy(), sendEmail);
        boolean hasConsented = consentService.hasUserConsentedToResearch(testUser.getUser(), testUser.getStudy());
        assertTrue(hasConsented);

        // Suspend sharing should make isSharingData return false.
        consentService.suspendDataSharing(testUser.getUser(), testUser.getStudy());
        boolean isSharing = consentService.isSharingData(testUser.getUser(), testUser.getStudy());
        assertFalse(isSharing);

        // Resume sharing should make isSharingData return true.
        consentService.resumeDataSharing(testUser.getUser(), testUser.getStudy());
        isSharing = consentService.isSharingData(testUser.getUser(), testUser.getStudy());

    }
}
