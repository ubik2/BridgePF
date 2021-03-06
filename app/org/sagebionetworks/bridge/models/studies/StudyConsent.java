package org.sagebionetworks.bridge.models.studies;

import org.sagebionetworks.bridge.json.BridgeTypeName;
import org.sagebionetworks.bridge.models.BridgeEntity;

@BridgeTypeName("StudyConsent")
public interface StudyConsent extends BridgeEntity {

    /**
     * The study associated with this consent.
     */
    String getStudyKey();

    /**
     * Timestamp when this consent is created.
     */
    long getCreatedOn();

    /**
     * Whether the consent is active.
     */
    boolean getActive();

    /**
     * Where to find the consent document.
     */
    String getPath();

    /**
     * Minimum age required to sign this consent.
     */
    int getMinAge();

}
