package org.sagebionetworks.bridge.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nonnull;

import com.google.common.base.Strings;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.cms.CMSException;

import org.sagebionetworks.bridge.crypto.CmsEncryptor;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.validators.Validate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Archives messages and then encrypts the archive using CMS.
 * Conversely also decrypts the archive and unpacks it.
 */
@Component
public class UploadArchiveService {
    private LoadingCache<String, CmsEncryptor> cmsEncryptorCache;

    /** Loading cache for CMS encryptor, keyed by study ID. This is configured by Spring. */
    @Autowired
    public void setCmsEncryptorCache(LoadingCache<String, CmsEncryptor> cmsEncryptorCache) {
        this.cmsEncryptorCache = cmsEncryptorCache;
    }

    /**
     * Encrypts the specified data, using the encryption materials for the specified study.
     *
     * @param studyId
     *         study ID, must be non-null, non-empty, and refer to a valid study
     * @param bytes
     *         data to encrypt, must be non-null
     * @return encrypted data as a byte array
     * @throws BridgeServiceException
     *         if we fail to load the encryptor, or if encryption fails
     */
    public byte[] encrypt(@Nonnull String studyId, @Nonnull byte[] bytes) throws BridgeServiceException {
        // validate
        if (Strings.isNullOrEmpty(studyId)) {
            throw new BadRequestException(String.format(Validate.CANNOT_BE_BLANK, "studyId"));
        }
        if (bytes == null) {
            throw new BadRequestException(String.format(Validate.CANNOT_BE_NULL, "bytes"));
        }

        // get encryptor from cache
        CmsEncryptor encryptor = getEncryptorForStudy(studyId);

        // encrypt
        byte[] encryptedData;
        try {
            encryptedData = encryptor.encrypt(bytes);
        } catch (CMSException | IOException ex) {
            throw new BridgeServiceException(ex);
        }
        return encryptedData;
    }

    /**
     * Decrypts the specified data, using the encryption materials for the specified study.
     *
     * @param studyId
     *         study ID, must be non-null, non-empty, and refer to a valid study
     * @param bytes
     *         data to decrypt, must be non-null
     * @return decrypted data as a byte array
     * @throws BridgeServiceException
     *         if we fail to load the encryptor, or if decryption fails
     */
    public byte[] decrypt(@Nonnull String studyId, @Nonnull byte[] bytes) throws BridgeServiceException {
        // validate
        if (Strings.isNullOrEmpty(studyId)) {
            throw new BadRequestException(String.format(Validate.CANNOT_BE_BLANK, "studyId"));
        }
        if (bytes == null) {
            throw new BadRequestException(String.format(Validate.CANNOT_BE_NULL, "bytes"));
        }

        // get encryptor from cache
        CmsEncryptor encryptor = getEncryptorForStudy(studyId);

        // decrypt
        try {
            return encryptor.decrypt(bytes);
        } catch (CertificateEncodingException | CMSException | IOException ex) {
            throw new BridgeServiceException(ex);
        }
    }

    /**
     * Helper function to get the encryptor for the given study.
     *
     * @param studyId
     *         study ID to get the encryptor for
     * @return the encryptor for the given study
     * @throws BridgeServiceException
     *         if we fail to load the encryptor, or if the encryptor can't be found
     */
    private @Nonnull CmsEncryptor getEncryptorForStudy(@Nonnull String studyId) throws BridgeServiceException {
        CmsEncryptor encryptor;
        try {
            encryptor = cmsEncryptorCache.get(studyId);
        } catch (ExecutionException | UncheckedExecutionException ex) {
            throw new BridgeServiceException(ex);
        }
        if (encryptor == null) {
            throw new BridgeServiceException(String.format("No encrypt for study %s", studyId));
        }
        return encryptor;
    }

    /**
     * Zips the given archive entries into a raw byte array.
     *
     * @param dataMap
     *         entries to zip, keyed by filename, must be non-null
     * @return byte array of data representing the zipped entries
     * @throws BridgeServiceException
     *         if zipping fails
     */
    public byte[] zip(@Nonnull Map<String, byte[]> dataMap) throws BridgeServiceException {
        if (dataMap == null) {
            throw new BadRequestException(String.format(Validate.CANNOT_BE_NULL, "dataMap"));
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> oneData : dataMap.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(oneData.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(oneData.getValue());
                zos.closeEntry();
            }
            zos.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new BridgeServiceException(ex);
        }
    }

    /**
     * <p>
     * Unzips the given byte array. The resulting map keys are the filenames of the data entries. The values are the
     * unzipped data entries as a byte array.
     * </p>
     * <p>
     * This method will throw a BadRequestException if the zip file somehow contains duplicate filenames.
     * </p>
     *
     * @param bytes
     *         byte array containing the raw data to unzip, must be non-null
     * @return raw bytes of unzipped data, keyed by filename
     * @throws BridgeServiceException
     *         if unzipping fails
     */
    public Map<String, byte[]> unzip(@Nonnull byte[] bytes) throws BridgeServiceException {
        if (bytes == null) {
            throw new BadRequestException(String.format(Validate.CANNOT_BE_NULL, "bytes"));
        }

        Map<String, byte[]> dataMap = new HashMap<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                ZipInputStream zis = new ZipInputStream(bais)) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                String entryName = zipEntry.getName();
                if (dataMap.containsKey(entryName)) {
                    throw new BadRequestException(String.format("Duplicate filename %s", entryName));
                }

                byte[] content = IOUtils.toByteArray(zis);
                dataMap.put(entryName, content);
                zipEntry = zis.getNextEntry();
            }
            return dataMap;
        } catch (IOException e) {
            throw new BridgeServiceException(e);
        }
    }
}
