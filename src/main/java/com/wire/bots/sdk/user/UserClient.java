//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk.user;

import com.wire.bots.cryptobox.CryptoException;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.assets.*;
import com.wire.bots.sdk.crypto.Crypto;
import com.wire.bots.sdk.exceptions.HttpException;
import com.wire.bots.sdk.models.AssetKey;
import com.wire.bots.sdk.models.otr.*;
import com.wire.bots.sdk.server.model.Conversation;
import com.wire.bots.sdk.server.model.NewBot;
import com.wire.bots.sdk.server.model.User;
import com.wire.bots.sdk.tools.Logger;
import com.wire.bots.sdk.tools.Util;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

public class UserClient implements WireClient {
    private final API api;
    private final Crypto crypto;
    private final NewBot state;
    private final UUID conv;
    private Devices devices = null;

    UserClient(NewBot state, UUID conv, Crypto crypto, API api) {
        this.state = state;
        this.api = api;
        this.crypto = crypto;
        this.conv = conv;
    }

    public void sendText(String txt) throws Exception {
        Text generic = new Text(txt);
        postGenericMessage(generic);
    }

    @Override
    public void sendText(String txt, long expires) throws Exception {
        postGenericMessage(new Text(txt, expires));
    }

    @Override
    public void sendDirectText(String txt, String userId) throws Exception {
        sendText(txt); //todo implement direct messaging
    }

    @Override
    public void sendLinkPreview(String url, String title, IGeneric image) throws Exception {
        postGenericMessage(new LinkPreview(url, title, image.createGenericMsg().getAsset()));
    }

    @Override
    public void sendDirectLinkPreview(String url, String title, IGeneric image, String userId) throws Exception {
        LinkPreview msg = new LinkPreview(url, title, image.createGenericMsg().getAsset());
        postGenericMessage(msg); //todo implement direct messaging
    }

    @Override
    public void sendText(String txt, long expires, String messageId) throws Exception {
        Text text = new Text(txt, expires);
        text.setMessageId(messageId);
        postGenericMessage(text);
    }

    @Override
    public void sendPicture(byte[] bytes, String mimeType) throws Exception {
        Picture image = new Picture(bytes, mimeType);

        AssetKey assetKey = uploadAsset(image);
        image.setAssetKey(assetKey.key);
        image.setAssetToken(assetKey.token);

        postGenericMessage(image);
    }

    @Override
    public void sendDirectPicture(byte[] bytes, String mimeType, String userId) throws Exception {
        sendPicture(bytes, mimeType);
    }

    @Override
    public void sendDirectPicture(IGeneric image, String userId) throws Exception {
        postGenericMessage(image);
    }

    @Override
    public AssetKey uploadAsset(IAsset asset) throws Exception {
        return api.uploadAsset(asset);
    }

    @Override
    public void sendPicture(IGeneric image) throws Exception {
        postGenericMessage(image);
    }

    @Override
    public void sendAudio(byte[] bytes, String name, String mimeType, long duration) throws Exception {
        AudioPreview preview = new AudioPreview(bytes, name, mimeType, duration);
        AudioAsset audioAsset = new AudioAsset(bytes, preview);

        postGenericMessage(preview);

        AssetKey assetKey = api.uploadAsset(audioAsset);
        audioAsset.setAssetKey(assetKey.key);
        audioAsset.setAssetToken(assetKey.token);

        // post original + remote asset message
        postGenericMessage(audioAsset);
    }

    @Override
    public void sendVideo(byte[] bytes, String name, String mimeType, long duration, int h, int w) throws Exception {
        String messageId = UUID.randomUUID().toString();
        VideoPreview preview = new VideoPreview(name, mimeType, duration, h, w, bytes.length, messageId);
        VideoAsset asset = new VideoAsset(bytes, mimeType, messageId);

        postGenericMessage(preview);

        AssetKey assetKey = uploadAsset(asset);
        asset.setAssetKey(assetKey.key);
        asset.setAssetToken(assetKey.token);

        // post original + remote asset message
        postGenericMessage(asset);
    }

    @Override
    public void sendFile(File f, String mime) throws Exception {
        String messageId = UUID.randomUUID().toString();
        FileAssetPreview preview = new FileAssetPreview(f.getName(), mime, f.length(), messageId);
        FileAsset asset = new FileAsset(f, mime, messageId);

        postGenericMessage(preview);

        // upload asset to backend
        AssetKey assetKey = api.uploadAsset(asset);
        asset.setAssetKey(assetKey.key);
        asset.setAssetToken(assetKey.token);

        // post original + remote asset message
        postGenericMessage(asset);
    }

    @Override
    public void sendDirectFile(IGeneric preview, IGeneric asset, String userId) throws Exception {
        postGenericMessage(preview);
        postGenericMessage(asset);
    }

    @Override
    public void sendDirectFile(File f, String mime, String userId) throws Exception {
        sendFile(f, mime);
    }

    @Override
    public void ping() throws Exception {
        postGenericMessage(new Ping());
    }

    @Override
    public void sendReaction(String msgId, String emoji) throws Exception {
        postGenericMessage(new Reaction(msgId, emoji));
    }

    @Override
    public void deleteMessage(String msgId) throws Exception {
        postGenericMessage(new Delete(msgId));
    }

    @Override
    public byte[] downloadAsset(String assetKey, String assetToken, byte[] sha256Challenge, byte[] otrKey)
            throws Exception {
        byte[] cipher = api.downloadAsset(assetKey, assetToken);
        byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(cipher);
        if (!Arrays.equals(sha256, sha256Challenge))
            throw new Exception("Failed sha256 check");

        return Util.decrypt(otrKey, cipher);
    }

    @Override
    public String getId() {
        return state.id;
    }

    @Override
    public UUID getConversationId() {
        return conv;
    }

    @Override
    public String getDeviceId() {
        return state.client;
    }

    @Override
    public Collection<User> getUsers(Collection<String> userIds) {
        return api.getUsers(userIds);
    }

    @Override
    public User getUser(String userId) {
        Collection<User> users = api.getUsers(Collections.singleton(userId));
        return users.iterator().next();
    }

    @Override
    public Conversation getConversation() throws IOException {
        return api.getConversation();
    }

    @Override
    public void acceptConnection(UUID user) throws Exception {
        api.acceptConnection(user);
    }

    private void postGenericMessage(IGeneric generic) throws Exception {
        byte[] content = generic.createGenericMsg().toByteArray();

        // Try to encrypt the msg for those devices that we have the session already
        Recipients encrypt = crypto.encrypt(getDevices().missing, content);
        OtrMessage msg = new OtrMessage(state.client, encrypt);

        Devices res = api.sendMessage(msg, false);
        if (!res.hasMissing()) {
            // Fetch preKeys for the missing devices from the Backend
            PreKeys preKeys = api.getPreKeys(res.missing);

            // Encrypt msg for those devices that were missing. This time using preKeys
            encrypt = crypto.encrypt(preKeys, content);
            msg.add(encrypt);

            // reset devices so they could be pulled next time
            devices = null;

            res = api.sendMessage(msg, true);
            if (!res.hasMissing()) {
                Logger.error(String.format("Failed to send otr message to %d devices. Bot: %s",
                        res.size(),
                        getId()));
            }
        }
    }

    /*
    This will close the Cryptobox and render this object useless
     */
    @Override
    public void close() throws IOException {
        crypto.close();
    }

    @Override
    public String decrypt(String userId, String clientId, String cypher) throws CryptoException {
        return crypto.decrypt(userId, clientId, cypher);
    }

    @Override
    public PreKey newLastPreKey() throws CryptoException {
        return crypto.newLastPreKey();
    }

    @Override
    public ArrayList<com.wire.bots.sdk.models.otr.PreKey> newPreKeys(int from, int count) throws CryptoException {
        return crypto.newPreKeys(from, count);
    }

    @Override
    public void uploadPreKeys(ArrayList<com.wire.bots.sdk.models.otr.PreKey> preKeys) {
        api.uploadPreKeys(preKeys);
    }

    @Override
    public ArrayList<Integer> getAvailablePrekeys() {
        return api.getAvailablePrekeys(state.client);
    }

    @Override
    public boolean isClosed() {
        return crypto.isClosed();
    }

    @Override
    public byte[] downloadProfilePicture(String assetKey) throws Exception {
        return api.downloadAsset(assetKey, null);
    }

    @Override
    public void call(String content) throws Exception {
        postGenericMessage(new Calling(content));
    }

    private Devices getDevices() throws HttpException {
        if (devices == null || devices.hasMissing()) {
            OtrMessage msg = new OtrMessage(state.client, new Recipients());
            devices = api.sendMessage(msg, false);
        }
        return devices;
    }
}
