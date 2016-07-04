/*
 * Copyright (c) 2009 - 2015 Deutsches Elektronen-Synchroton,
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
package org.dcache.nfs.v4;

import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.nfsstat;
import org.dcache.nfs.status.BadStateidException;
import org.dcache.nfs.status.InvalException;
import org.dcache.nfs.status.OldStateidException;
import org.dcache.nfs.status.OpenModeException;
import org.dcache.nfs.status.ServerFaultException;
import org.dcache.nfs.v4.nlm.LockDeniedException;
import org.dcache.nfs.v4.nlm.LockException;
import org.dcache.nfs.v4.nlm.NlmLock;
import org.dcache.nfs.v4.xdr.LOCK4denied;
import org.dcache.nfs.v4.xdr.LOCK4resok;
import org.dcache.nfs.v4.xdr.length4;
import org.dcache.nfs.v4.xdr.lock_owner4;
import org.dcache.nfs.v4.xdr.nfs4_prot;
import org.dcache.nfs.v4.xdr.nfs_argop4;
import org.dcache.nfs.v4.xdr.nfs_lock_type4;
import org.dcache.nfs.v4.xdr.nfs_opnum4;
import org.dcache.nfs.v4.xdr.nfs_resop4;
import org.dcache.nfs.v4.xdr.offset4;
import org.dcache.nfs.v4.xdr.stateid4;
import org.dcache.nfs.vfs.Inode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationLOCK extends AbstractNFSv4Operation {

    private static final Logger _log = LoggerFactory.getLogger(OperationLOCK.class);

    public OperationLOCK(nfs_argop4 args) {
        super(args, nfs_opnum4.OP_LOCK);
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException {
        // to enforce current file handle existence check
        Inode inode = context.currentInode();

        if(_args.oplock.length.value == 0) {
            throw new InvalException("zerro lock len");
        }

        _args.oplock.offset.checkOverflow(_args.oplock.length, "offset + len overflow");

        stateid4 oldStateid;
        NFS4Client client;
        NFS4State lock_state;
        lock_owner4 lockOwner;

        if (_args.oplock.locker.new_lock_owner) {
            oldStateid = Stateids.getCurrentStateidIfNeeded(context, _args.oplock.locker.open_owner.open_stateid);

            if(context.getMinorversion() == 0) {
                client = context.getStateHandler().getClient(_args.oplock.locker.open_owner.lock_owner.clientid);
                client.validateSequence(_args.oplock.locker.open_owner.open_seqid);
                lockOwner = _args.oplock.locker.open_owner.lock_owner;
            } else {
                client = context.getSession().getClient();
                lockOwner = new lock_owner4(client.asStateOwner());
                // takse client id from session, byt use the provided ownerr as
                // required by http://tools.ietf.org/html/rfc5661#section-18.11.3
                lockOwner.owner = _args.oplock.locker.open_owner.lock_owner.owner;
            }

            NFS4State openState = client.state(oldStateid);

            if (openState.stateid().seqid.value > oldStateid.seqid.value) {
                throw new OldStateidException();
            }

            if (openState.stateid().seqid.value < oldStateid.seqid.value) {
                throw new BadStateidException();
            }

            // check open file mode
            int shareAccess = context
                    .getStateHandler()
                    .getFileTracker()
                    .getShareAccess(client, inode,
                            openState.getParentState().stateid());

            if ( (shareAccess & nfs4_prot.OPEN4_SHARE_ACCESS_WRITE) == 0 &&
                ((_args.oplock.locktype == nfs_lock_type4.WRITEW_LT) || (_args.oplock.locktype == nfs_lock_type4.WRITE_LT))) {
                throw new OpenModeException("can't provide WRITE locl on read-only open");
            }
            lock_state = client.createState(lockOwner, openState);

        } else {
            oldStateid = Stateids.getCurrentStateidIfNeeded(context, _args.oplock.locker.lock_owner.lock_stateid);
            client = context.getStateHandler().getClientIdByStateId(oldStateid);
            lock_state = client.state(oldStateid);

            if(lock_state.stateid().seqid.value > oldStateid.seqid.value) {
                throw new OldStateidException();
            }

            if (lock_state.stateid().seqid.value < oldStateid.seqid.value) {
                throw new BadStateidException();
            }

            lockOwner = new lock_owner4(lock_state.getStateOwner());
        }

        try {
            NlmLock lock = new NlmLock(lockOwner, _args.oplock.locktype,  _args.oplock.offset.value, _args.oplock.length.value);
            context.getLm().lock(inode.getFileId(), lock);

            // ensure, that on close locks will be cleaned
            lock_state.addDisposeListener(s -> context.getLm().unlockIfExists(inode.getFileId(), lock));

            lock_state.bumpSeqid();
            context.currentStateid(lock_state.stateid());
            result.oplock.status = nfsstat.NFS_OK;
            result.oplock.resok4 = new LOCK4resok();
            result.oplock.resok4.lock_stateid = lock_state.stateid();

        } catch (LockDeniedException e) {
            result.oplock.status = nfsstat.NFSERR_DENIED;
            NlmLock conflictingLock = e.getConflictingLock();
            result.oplock.denied = new LOCK4denied();
            result.oplock.denied.offset = new offset4(conflictingLock.getOffset());
            result.oplock.denied.length = new length4(conflictingLock.getLength());
            result.oplock.denied.locktype = conflictingLock.getLockType();
            result.oplock.denied.owner = conflictingLock.getOwner();
        } catch (LockException e) {
            throw new ServerFaultException("lock error", e);
        }

    }

}
