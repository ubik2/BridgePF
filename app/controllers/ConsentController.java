package controllers;

import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.models.ResearchConsent;
import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.UserSession;
import org.sagebionetworks.bridge.services.ConsentService;

import play.mvc.Result;

public class ConsentController extends BaseController {

    private StudyControllerService studyControllerService;
    private ConsentService consentService;

    public void setStudyControllerService(StudyControllerService studyControllerService) {
        this.studyControllerService = studyControllerService;
    }

    public void setConsentService(ConsentService consentService) {
        this.consentService = consentService;
    }

    public Result give() throws Exception {
        // Don't call getSession(), it'll throw an exception due to lack of
        // consent, we know this person has not consented, that's what they're
        // trying to do.
        UserSession session = checkForSession();
        if (session == null) {
            throw new BridgeServiceException("Not signed in.", 401);
        }
        ResearchConsent consent = ResearchConsent.fromJson(request().body().asJson());
        Study study = studyControllerService.getStudyByHostname(request());
        consentService.consentToResearch(session.getSessionToken(), consent, study, true);

        return jsonResult("Consent to research has been recorded.");
    }

    public Result withdraw() throws Exception {
        UserSession session = getSession(); // throws exception if user isn't consented
        if (session == null) {
            throw new BridgeServiceException("Not signed in.", 401);
        } else if (!session.doesConsent()) {
            throw new BridgeServiceException("Need to consent.", 412);
        }
        Study study = studyControllerService.getStudyByHostname(request());
        consentService.withdrawConsent(session.getSessionToken(), study);

        return jsonResult("Withdraw consent has been recorded.");
    }

    public Result emailCopy() throws Exception {
        UserSession session = getSession();
        if (session == null) {
            throw new BridgeServiceException("Not signed in.", 401);
        } else if (!session.doesConsent()) {
            throw new BridgeServiceException("Need to consent.", 412);
        }
        Study study = studyControllerService.getStudyByHostname(request());
        consentService.emailConsentAgreement(session.getSessionToken(), study);

        return jsonResult("Emailed consent.");
    }
}
