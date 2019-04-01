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

package com.wire.bots.sdk.server.resources;

import com.wire.bots.cryptobox.CryptoException;
import com.wire.bots.sdk.ClientRepo;
import com.wire.bots.sdk.MessageHandlerBase;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.exceptions.MissingStateException;
import com.wire.bots.sdk.server.model.ErrorMessage;
import com.wire.bots.sdk.server.model.Payload;
import com.wire.bots.sdk.tools.AuthValidator;
import com.wire.bots.sdk.tools.Logger;
import io.swagger.annotations.*;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.UUID;

@Api
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Path("/bots/{bot}/messages")
public class MessageResource extends MessageResourceBase {
    private final AuthValidator validator;

    public MessageResource(MessageHandlerBase handler, AuthValidator validator, ClientRepo repo) {
        super(handler, repo);
        this.validator = validator;
    }

    @POST
    @ApiOperation(value = "New OTR Message")
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "Invalid Authorization", response = ErrorMessage.class),
            @ApiResponse(code = 503, message = "Missing bot's state object", response = ErrorMessage.class),
            @ApiResponse(code = 200, message = "Alles gute")})
    public Response newMessage(@ApiParam("Service token") @HeaderParam("Authorization") @NotNull String auth,
                               @ApiParam("Bot instance id") @PathParam("bot") String botId,
                               @ApiParam @Valid @NotNull Payload inbound) {

        if (!validator.validate(auth)) {
            Logger.warning("%s, Invalid auth. Got: '%s'", botId, auth);
            return Response.
                    status(401).
                    entity(new ErrorMessage("Invalid Authorization token")).
                    build();
        }

        try (WireClient client = repo.getClient(botId)) {
            handleMessage(inbound, client);
        } catch (CryptoException e) {
            Logger.error("MessageResource::newMessage: bot: %s %s", botId, e);
            respondWithError(botId, "Something went wrong");
            return Response.
                    status(503).
                    entity(new ErrorMessage(e.getMessage())).
                    build();
        } catch (MissingStateException e) {
            Logger.error("MessageResource::newMessage: bot: %s %s", botId, e);
            return Response.
                    status(410).
                    entity(new ErrorMessage(e.getMessage())).
                    build();
        } catch (Exception e) {
            Logger.error("MessageResource::newMessage: bot: %s %s", botId, e);
            return Response.
                    status(400).
                    entity(new ErrorMessage(e.getMessage())).
                    build();
        }

        return Response.
                ok().
                status(200).
                build();
    }

    private void respondWithError(String botId, String message) {
        try (WireClient client = repo.getClient(botId)) {
            client.sendReaction(UUID.randomUUID(), message);
        } catch (Exception e1) {
            Logger.error("MessageResource::newMessage: bot: %s %s", botId, e1);
        }
    }
}
