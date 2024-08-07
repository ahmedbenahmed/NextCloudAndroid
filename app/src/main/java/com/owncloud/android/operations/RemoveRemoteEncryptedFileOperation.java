/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2017 Tobias Kaminsky <tobias@kaminsky.me>
 * SPDX-FileCopyrightText: 2017 Nextcloud GmbH
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */

package com.owncloud.android.operations;

import android.content.Context;

import com.nextcloud.client.account.User;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.e2e.v2.decrypted.DecryptedFolderMetadataFile;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.utils.EncryptionUtils;
import com.owncloud.android.utils.EncryptionUtilsV2;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.jackrabbit.webdav.client.methods.DeleteMethod;

import kotlin.Pair;

/**
 * Remote operation performing the removal of a remote encrypted file or folder
 */
public class RemoveRemoteEncryptedFileOperation extends RemoteOperation<Void> {
    private static final String TAG = RemoveRemoteEncryptedFileOperation.class.getSimpleName();
    private static final int REMOVE_READ_TIMEOUT = 30000;
    private static final int REMOVE_CONNECTION_TIMEOUT = 5000;
    private final String remotePath;
    private final OCFile parentFolder;
    private final User user;
    private final String fileName;
    private final Context context;
    private final boolean isFolder;

    /**
     * Constructor
     *
     * @param remotePath   RemotePath of the remote file or folder to remove from the server
     * @param parentFolder parent folder
     */
    RemoveRemoteEncryptedFileOperation(String remotePath,
                                       User user,
                                       Context context,
                                       String fileName,
                                       OCFile parentFolder,
                                       boolean isFolder) {
        this.remotePath = remotePath;
        this.user = user;
        this.fileName = fileName;
        this.context = context;
        this.parentFolder = parentFolder;
        this.isFolder = isFolder;
    }

    /**
     * Performs the remove operation.
     */
    @Override
    protected RemoteOperationResult<Void> run(OwnCloudClient client) {
        RemoteOperationResult<Void> result;
        DeleteMethod delete = null;
        String token = null;

        try {
            // Lock folder
            token = EncryptionUtils.lockFolder(parentFolder, client);

            EncryptionUtilsV2 encryptionUtilsV2 = new EncryptionUtilsV2();
            Pair<Boolean, DecryptedFolderMetadataFile> pair = encryptionUtilsV2.retrieveMetadata(parentFolder, client, user, context);
            boolean metadataExists = pair.getFirst();
            DecryptedFolderMetadataFile metadata = pair.getSecond();

            // delete file remote
            delete = new DeleteMethod(client.getFilesDavUri(remotePath));
            delete.setQueryString(new NameValuePair[]{new NameValuePair(E2E_TOKEN, token)});
            int status = client.executeMethod(delete, REMOVE_READ_TIMEOUT, REMOVE_CONNECTION_TIMEOUT);

            delete.getResponseBodyAsString();   // exhaust the response, although not interesting
            result = new RemoteOperationResult<>(delete.succeeded() || status == HttpStatus.SC_NOT_FOUND, delete);
            Log_OC.i(TAG, "Remove " + remotePath + ": " + result.getLogMessage());

            if (isFolder) {
                encryptionUtilsV2.removeFolderFromMetadata(fileName, metadata);
            } else {
                encryptionUtilsV2.removeFileFromMetadata(fileName, metadata);
            }

            // upload metadata
            encryptionUtilsV2.serializeAndUploadMetadata(parentFolder,
                                                         metadata,
                                                         token,
                                                         client,
                                                         metadataExists,
                                                         context,
                                                         user,
                                                         new FileDataStorageManager(user, context.getContentResolver()));

            // return success
            return result;
        } catch (Exception e) {
            result = new RemoteOperationResult<>(e);
            Log_OC.e(TAG, "Remove " + remotePath + ": " + result.getLogMessage(), e);

        } finally {
            if (delete != null) {
                delete.releaseConnection();
            }

            // unlock file
            if (token != null) {
                RemoteOperationResult<Void> unlockFileOperationResult = EncryptionUtils.unlockFolder(parentFolder,
                                                                                                     client,
                                                                                                     token);

                if (!unlockFileOperationResult.isSuccess()) {
                    Log_OC.e(TAG, "Failed to unlock " + parentFolder.getLocalId());
                }
            }
        }

        return result;
    }
}
