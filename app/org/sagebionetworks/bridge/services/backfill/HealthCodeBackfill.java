package org.sagebionetworks.bridge.services.backfill;

import java.util.Iterator;

import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.models.BackfillTask;
import org.sagebionetworks.bridge.models.HealthId;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.HealthCodeService;
import org.sagebionetworks.bridge.services.StudyService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Backfills health ID and health code.
 */
public class HealthCodeBackfill extends AsyncBackfillTemplate {

    private BackfillRecordFactory backfillRecordFactory;
    private AccountDao accountDao;
    private StudyService studyService;
    private HealthCodeService healthCodeService;

    @Autowired
    public void setBackfillRecordFactory(BackfillRecordFactory backfillRecordFactory) {
        this.backfillRecordFactory = backfillRecordFactory;
    }
    
    @Autowired
    public void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }

    @Autowired
    public void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    
    @Autowired
    public void setHealthCodeService(HealthCodeService healthCodeService) {
        this.healthCodeService = healthCodeService;
    }

    @Override
    int getLockExpireInSeconds() {
        return 30 * 60;
    }

    @Override
    void doBackfill(final BackfillTask task, BackfillCallback callback) {
        for (Iterator<Account> i = accountDao.getAllAccounts(); i.hasNext();) {
            Account account = i.next();
            Study study = studyService.getStudy(account.getStudyIdentifier());
            
            HealthId mapping = healthCodeService.getMapping(account);
            if (mapping == null) {
                // Backfill the account that have no health code mapping in the study.
                // This happens when the user creates a new account and consents in a study
                // and has not consented in other studies yet.
                mapping = healthCodeService.createMapping(study);
                account.setHealthId(mapping.getId());
                accountDao.updateAccount(study, account);
                callback.newRecords(backfillRecordFactory.createAndSave(
                        task, study, account, "health code created"));
            } 
        }
    }
}
