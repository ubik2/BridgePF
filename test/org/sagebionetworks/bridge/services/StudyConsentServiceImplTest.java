package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.TestUserAdminHelper;
import org.sagebionetworks.bridge.dynamodb.DynamoStudyConsentDao;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.models.studies.StudyConsent;
import org.sagebionetworks.bridge.models.studies.StudyConsentForm;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class StudyConsentServiceImplTest {

    @Resource
    private StudyConsentService studyConsentService;

    @Resource
    private TestUserAdminHelper helper;

    @Resource
    private DynamoStudyConsentDao studyConsentDao;
 
    private List<StudyConsent> toDelete;

    @Before
    public void before() {
        toDelete = new ArrayList<StudyConsent>();
    }

    @After
    public void after() {
        for (StudyConsent sc : toDelete) {
            studyConsentDao.deleteConsent(new StudyIdentifierImpl(sc.getStudyKey()), sc.getCreatedOn());
        }
        toDelete.clear();
    }

    @Test
    public void test() {

        StudyIdentifier studyId = new StudyIdentifierImpl("study-key");
        String path = "fake-path";
        int minAge = 17;
        StudyConsentForm form = new StudyConsentForm(path, minAge);

        // addConsent should return a non-null consent object.
        StudyConsent addedConsent1 = studyConsentService.addConsent(studyId, form);
        assertNotNull(addedConsent1);
        toDelete.add(addedConsent1);

        try {
            studyConsentService.getActiveConsent(studyId);
            fail("getActiveConsent should throw exception, as there is no currently active consent.");
        } catch (Exception e) {
        }

        // Get active consent returns the most recently activated consent document.
        StudyConsent activatedConsent = studyConsentService.activateConsent(studyId, addedConsent1.getCreatedOn());
        StudyConsent getActiveConsent = studyConsentService.getActiveConsent(studyId);
        assertTrue(activatedConsent.getCreatedOn() == getActiveConsent.getCreatedOn());

        // Get all consents returns one consent document (addedConsent).
        List<StudyConsent> allConsents = studyConsentService.getAllConsents(studyId);
        assertTrue(allConsents.size() == 1);

        // Cannot delete active consent document.
        try {
            studyConsentService.deleteConsent(studyId, getActiveConsent.getCreatedOn());
            fail("Was able to successfully delete active consent, which we should not be able to do.");
        } catch (BridgeServiceException e) {
        }
    }
}
