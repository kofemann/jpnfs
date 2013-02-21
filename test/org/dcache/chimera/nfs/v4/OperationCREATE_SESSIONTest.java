/*
 * Copyright (c) 2009 - 2012 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.chimera.nfs.v4;

import java.util.UUID;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.v4.client.CreateSessionStub;
import org.dcache.chimera.nfs.v4.client.DestroySessionStub;
import org.dcache.chimera.nfs.v4.client.ExchangeIDStub;
import org.dcache.chimera.nfs.v4.xdr.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class OperationCREATE_SESSIONTest {

    private NFSv4StateHandler stateHandler;
    private final String domain = "nairi.desy.de";
    private final String name = "dCache.ORG java based client";
    private String clientId;

    @Before
    public void setUp() {
        stateHandler = new NFSv4StateHandler();
        clientId = UUID.randomUUID().toString();
    }

    @Test
    public void testCreateSession() throws Exception {
        CompoundContext context;
        nfs_resop4 result;

        nfs_argop4 exchangeid_args = ExchangeIDStub.normal(domain, name, clientId, 0, state_protect_how4.SP4_NONE);
        OperationEXCHANGE_ID EXCHANGE_ID = new OperationEXCHANGE_ID(exchangeid_args, 0);

        result = nfs_resop4.resopFor(nfs_opnum4.OP_EXCHANGE_ID);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

        AssertNFS.assertNFS(EXCHANGE_ID, context, result, nfsstat.NFS_OK);

        nfs_argop4 cretaesession_args = CreateSessionStub.standard(
                result.opexchange_id.eir_resok4.eir_clientid, result.opexchange_id.eir_resok4.eir_sequenceid);

        OperationCREATE_SESSION CREATE_SESSION = new OperationCREATE_SESSION(cretaesession_args);
        result = nfs_resop4.resopFor(nfs_opnum4.OP_CREATE_SESSION);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

         AssertNFS.assertNFS(CREATE_SESSION, context, result, nfsstat.NFS_OK);
    }

    @Test
    public void testCreateSessionNoClient() throws Exception {
        CompoundContext context;
        nfs_resop4 result;

        nfs_argop4 cretaesession_args = CreateSessionStub.standard(
                new clientid4( new uint64_t(0)), new sequenceid4( new uint32_t(0)));

        OperationCREATE_SESSION CREATE_SESSION = new OperationCREATE_SESSION(cretaesession_args);
        result = nfs_resop4.resopFor(nfs_opnum4.OP_CREATE_SESSION);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

       AssertNFS.assertNFS(CREATE_SESSION, context, result, nfsstat.NFSERR_STALE_CLIENTID);
    }

    @Test
    public void testCreateSessionMisordered() throws Exception {
        CompoundContext context;
        nfs_resop4 result;

        nfs_argop4 exchangeid_args = ExchangeIDStub.normal(domain, name, clientId, 0, state_protect_how4.SP4_NONE);
        OperationEXCHANGE_ID EXCHANGE_ID = new OperationEXCHANGE_ID(exchangeid_args, 0);

        result = nfs_resop4.resopFor(nfs_opnum4.OP_EXCHANGE_ID);
        context = new CompoundContextBuilder().withStateHandler(stateHandler).withOpCount(1).build();

        AssertNFS.assertNFS(EXCHANGE_ID, context, result, nfsstat.NFS_OK);

        sequenceid4 badSequence = new sequenceid4(
                new uint32_t(result.opexchange_id.eir_resok4.eir_sequenceid.value.value +1)
                );
        nfs_argop4 cretaesession_args = CreateSessionStub.standard(
                result.opexchange_id.eir_resok4.eir_clientid, badSequence);

        OperationCREATE_SESSION CREATE_SESSION = new OperationCREATE_SESSION(cretaesession_args);
        result = nfs_resop4.resopFor(nfs_opnum4.OP_CREATE_SESSION);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

        AssertNFS.assertNFS(CREATE_SESSION, context, result, nfsstat.NFSERR_SEQ_MISORDERED);
    }

    @Test
    public void testDestroySession() throws Exception {
        CompoundContext context;
        nfs_resop4 result;

        nfs_argop4 exchangeid_args = ExchangeIDStub.normal(domain, name, clientId, 0, state_protect_how4.SP4_NONE);
        OperationEXCHANGE_ID EXCHANGE_ID = new OperationEXCHANGE_ID(exchangeid_args, 0);

        result = nfs_resop4.resopFor(nfs_opnum4.OP_EXCHANGE_ID);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

        AssertNFS.assertNFS(EXCHANGE_ID, context, result, nfsstat.NFS_OK);

        nfs_argop4 cretaesession_args = CreateSessionStub.standard(
                result.opexchange_id.eir_resok4.eir_clientid, result.opexchange_id.eir_resok4.eir_sequenceid);

        OperationCREATE_SESSION CREATE_SESSION = new OperationCREATE_SESSION(cretaesession_args);
        result = nfs_resop4.resopFor(nfs_opnum4.OP_CREATE_SESSION);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

        AssertNFS.assertNFS(CREATE_SESSION, context, result, nfsstat.NFS_OK);

        sessionid4 session = result.opcreate_session.csr_resok4.csr_sessionid;
        nfs_argop4 destroysession_args = DestroySessionStub.standard(result.opcreate_session.csr_resok4.csr_sessionid);
        OperationDESTROY_SESSION DESTROY_SESSION = new OperationDESTROY_SESSION(destroysession_args);
        result = nfs_resop4.resopFor(nfs_opnum4.OP_DESTROY_SESSION);
        context = new CompoundContextBuilder()
                .withStateHandler(stateHandler)
                .withOpCount(1)
                .build();

        AssertNFS.assertNFS(DESTROY_SESSION, context, result, nfsstat.NFS_OK);
        assertNull(stateHandler.sessionById(session));
    }
}
