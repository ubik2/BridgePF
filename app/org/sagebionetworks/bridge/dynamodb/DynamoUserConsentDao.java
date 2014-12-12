package org.sagebionetworks.bridge.dynamodb;

import java.util.List;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.UserConsent;
import org.sagebionetworks.bridge.models.studies.ConsentSignature;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyConsent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig.ConsistentReads;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig.SaveBehavior;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.google.common.collect.Sets;

public class DynamoUserConsentDao implements UserConsentDao {

    private DynamoDBMapper mapper;

    public void setDynamoDbClient(AmazonDynamoDB client) {
        DynamoDBMapperConfig mapperConfig = new DynamoDBMapperConfig.Builder().withSaveBehavior(SaveBehavior.UPDATE)
                .withConsistentReads(ConsistentReads.CONSISTENT)
                .withTableNameOverride(TableNameOverrideFactory.getTableNameOverride(DynamoUserConsent2.class)).build();
        mapper = new DynamoDBMapper(client, mapperConfig);
    }

    @Override
    public void giveConsent(String healthCode, StudyConsent consent, ConsentSignature consentSignature) {
        giveConsent2(healthCode, consent, consentSignature);
    }

    @Override
    public void withdrawConsent(String healthCode, StudyConsent consent) {
        withdrawConsent2(healthCode, consent);
    }

    @Override
    public boolean withdrawConsent(String healthCode, Study study) {
        // DynamoUserConsent2 has the healthCodeStudy as a hash key and no range key; so 
        // there can be only one consent right now per study. Just find it and delete it.
        DynamoUserConsent2 consent = new DynamoUserConsent2(healthCode, study.getIdentifier());
        consent = mapper.load(consent);
        if (consent != null) {
            mapper.delete(consent);
            return true;
        }
        return false;
    }
    
    @Override
    public Long getConsentCreatedOn(String healthCode, String studyKey) {
        return getConsentCreatedOn2(healthCode, studyKey);
    }

    @Override
    public boolean hasConsented(String healthCode, StudyConsent consent) {
        boolean hasConsented = hasConsented2(healthCode, consent);
        return hasConsented;
    }

    @Override
    public UserConsent getUserConsent(String healthCode, StudyConsent studyConsent) {
        DynamoUserConsent2 consent = new DynamoUserConsent2(healthCode, studyConsent);
        return mapper.load(consent);
    }

    /** Returns a non-null consent signature. Throws EntityNotFoundException if no consent signature is found. */
    @Override
    public ConsentSignature getConsentSignature(String healthCode, StudyConsent consent) {
        ConsentSignature signature = getConsentSignature2(healthCode, consent);
        return signature;
    }

    void giveConsent2(String healthCode, StudyConsent studyConsent, ConsentSignature researchConsent) {
        DynamoUserConsent2 consent = null;
        try {
            consent = new DynamoUserConsent2(healthCode, studyConsent);
            consent = mapper.load(consent);
            if (consent == null) { // If the user has not consented yet
                consent = new DynamoUserConsent2(healthCode, studyConsent);
            }
            consent.setName(researchConsent.getName());
            consent.setBirthdate(researchConsent.getBirthdate());
            consent.setImageData(researchConsent.getImageData());
            consent.setImageMimeType(researchConsent.getImageMimeType());
            consent.setSignedOn(DateTime.now(DateTimeZone.UTC).getMillis());
            mapper.save(consent);
        } catch (ConditionalCheckFailedException e) {
            throw new EntityAlreadyExistsException(consent);
        }
    }

    void withdrawConsent2(String healthCode, StudyConsent studyConsent) {
        DynamoUserConsent2 consentToDelete = new DynamoUserConsent2(healthCode, studyConsent);
        consentToDelete = mapper.load(consentToDelete);
        if (consentToDelete == null) {
            return;
        }
        mapper.delete(consentToDelete);
    }
    
    Long getConsentCreatedOn2(String healthCode, String studyKey) {
        DynamoUserConsent2 consent = new DynamoUserConsent2(healthCode, studyKey);
        consent = mapper.load(consent);
        return consent == null ? null : consent.getConsentCreatedOn();
    }

    boolean hasConsented2(String healthCode, StudyConsent studyConsent) {
        DynamoUserConsent2 consent = new DynamoUserConsent2(healthCode, studyConsent);
        return mapper.load(consent) != null;
    }

    /** Returns a non-null consent signature. Throws EntityNotFoundException if no consent signature is found. */
    ConsentSignature getConsentSignature2(String healthCode, StudyConsent studyConsent) {
        DynamoUserConsent2 consent = new DynamoUserConsent2(healthCode, studyConsent);
        consent = mapper.load(consent);
        if (consent == null) {
            throw new EntityNotFoundException(DynamoUserConsent2.class);
        }
        return ConsentSignature.create(consent.getName(), consent.getBirthdate(), consent.getImageData(),
                consent.getImageMimeType());
    }

    @Override
    public long getNumberOfParticipants(String studyKey) {
        DynamoDBScanExpression scan = new DynamoDBScanExpression();
        
        Condition condition = new Condition();
        condition.withComparisonOperator(ComparisonOperator.EQ);
        condition.withAttributeValueList(new AttributeValue().withS(studyKey));
        scan.addFilterCondition("studyKey", condition);
        
        Set<String> healthCodes = Sets.newHashSet();
        List<DynamoUserConsent2> mappings = mapper.scan(DynamoUserConsent2.class, scan);
        for (DynamoUserConsent2 consent : mappings) {
            healthCodes.add(consent.getHealthCode());
        }
        return healthCodes.size();
    }
}
