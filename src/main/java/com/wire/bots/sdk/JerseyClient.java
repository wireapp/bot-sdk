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

package com.wire.bots.sdk;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.wire.bots.sdk.assets.IAsset;
import com.wire.bots.sdk.models.AssetKey;
import com.wire.bots.sdk.models.otr.Devices;
import com.wire.bots.sdk.models.otr.OtrMessage;
import com.wire.bots.sdk.models.otr.PreKey;
import com.wire.bots.sdk.models.otr.PreKeys;
import com.wire.bots.sdk.server.model.Conversation;
import com.wire.bots.sdk.server.model.NewBotResponseModel;
import com.wire.bots.sdk.server.model.User;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.JerseyClientBuilder;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

class JerseyClient {

    private final static Client client;
    private final static String httpUrl;
    private final String token;

    static {
        String env = System.getProperty("env", "prod");
        String domain = env.equals("prod") ? "wire.com" : "zinfra.io"; //fixme: remove zinfra
        httpUrl = String.format("https://%s-nginz-https.%s", env, domain);

        ClientConfig cfg = new ClientConfig(JacksonJsonProvider.class);
        client = JerseyClientBuilder.createClient(cfg);
    }

    public JerseyClient(String token) {
        this.token = token;
    }

    /**
     * This method sends the OtrMessage to BE. Message must contain cipher for all participants and all their clients.
     *
     * @param msg           OtrMessage object containing ciphers for all clients
     * @param ignoreMissing If TRUE ignore missing clients and deliver the message to available clients
     * @return List of missing devices in case of fail or an empty list.
     * @throws IOException
     */
    Devices sendMessage(OtrMessage msg, boolean ignoreMissing) throws IOException {
        Response response = client.target(httpUrl).
                path("bot/messages").
                queryParam("ignore_missing", ignoreMissing).
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                post(Entity.entity(msg, MediaType.APPLICATION_JSON));

        int statusCode = response.getStatus();
        if (statusCode == 412) {
            // This message was not sent due to missing clients. Parse those missing clients so the caller can add them
            return response.readEntity(Devices.class);
        }

        if (statusCode >= 300) {
            String log = String.format("sendMessage: %s code: %d",
                    response.readEntity(String.class),
                    statusCode);
            throw new IOException(log);
        }

        return response.readEntity(Devices.class);
    }

    Devices sendMessage(OtrMessage msg) throws IOException {
        return sendMessage(msg, false);
    }

    Collection<User> getUsers(ArrayList<String> ids) throws IOException {
        return client.target(httpUrl).
                path("bot/users").
                queryParam("ids", String.join(",", ids)).
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                get(new GenericType<ArrayList<User>>() {
                });
    }

    Conversation getConversation() {
        return client.target(httpUrl).
                path("bot/conversation").
                request().
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                get(Conversation.class);
    }

    PreKeys getPreKeys(HashMap<String, ArrayList<String>> devices) {
        return client.target(httpUrl).
                path("bot/users/prekeys").
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                post(Entity.entity(devices, MediaType.APPLICATION_JSON), PreKeys.class);
    }

    ArrayList<Integer> getAvailablePrekeys() {
        return client.target(httpUrl).
                path("/bot/client/prekeys").
                request().
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                get(new GenericType<ArrayList<Integer>>() {
                });
    }

    public boolean uploadPreKeys(ArrayList<PreKey> preKeys) throws IOException {
        NewBotResponseModel model = new NewBotResponseModel();
        model.preKeys = preKeys;

        Response res = client.target(httpUrl).
                path("bot/client/prekeys").
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                post(Entity.entity(model, MediaType.APPLICATION_JSON));

        int statusCode = res.getStatus();
        if (statusCode >= 300) {
            String log = String.format("uploadPreKeys: %s code: %d",
                    res.readEntity(String.class),
                    statusCode);
            throw new IOException(log);
        }

        return statusCode == 200;
    }

    public AssetKey uploadAsset(IAsset asset) throws Exception {
        StringBuilder sb = new StringBuilder();

        // Part 1
        String strMetadata = String.format("{\"public\": %s, \"retention\": \"%s\"}",
                asset.isPublic(),
                asset.getRetention());

        sb.append("--frontier\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Content-Length: ")
                .append(strMetadata.length())
                .append("\r\n\r\n");
        sb.append(strMetadata)
                .append("\r\n");

        // Part 2
        sb.append("--frontier\r\n");
        sb.append("Content-Type: ")
                .append(asset.getMimeType())
                .append("\r\n");
        sb.append("Content-Length: ")
                .append(asset.getEncryptedData().length)
                .append("\r\n");
        sb.append("Content-MD5: ")
                .append(Util.calcMd5(asset.getEncryptedData()))
                .append("\r\n\r\n");

        // Complete
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(sb.toString().getBytes("utf-8"));
        os.write(asset.getEncryptedData());
        os.write("\r\n--frontier--\r\n".getBytes("utf-8"));

        Response response = client.target(httpUrl)
                .path("bot/assets")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .header("Authorization", "Bearer " + token)
                .post(Entity.entity(os.toByteArray(), "multipart/mixed; boundary=frontier"));

        if (response.getStatus() >= 300) {
            Logger.warning(response.readEntity(String.class));
            throw new IOException(response.getStatusInfo().getReasonPhrase());
        }

        return response.readEntity(AssetKey.class);
    }

    byte[] downloadAsset(String assetKey, String assetToken) throws IOException {
        Invocation.Builder req = client.target(httpUrl)
                .path("bot/assets")
                .path(assetKey)
                .request()
                .header("Authorization", "Bearer " + token);

        if (assetToken != null)
            req.header("Asset-Token", assetToken);

        Response response = req.get();

        if (response.getStatus() >= 300) {
            Logger.warning(response.readEntity(String.class));
            throw new IOException(response.getStatusInfo().getReasonPhrase());
        }

        return response.readEntity(byte[].class);
    }
}
