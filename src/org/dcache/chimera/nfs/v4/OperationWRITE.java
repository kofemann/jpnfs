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

import java.io.IOException;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.v4.xdr.uint32_t;
import org.dcache.chimera.nfs.v4.xdr.verifier4;
import org.dcache.chimera.nfs.v4.xdr.stable_how4;
import org.dcache.chimera.nfs.v4.xdr.nfs_argop4;
import org.dcache.chimera.nfs.v4.xdr.nfs4_prot;
import org.dcache.chimera.nfs.v4.xdr.nfs_opnum4;
import org.dcache.chimera.nfs.v4.xdr.count4;
import org.dcache.chimera.nfs.v4.xdr.WRITE4resok;
import org.dcache.chimera.nfs.v4.xdr.WRITE4res;
import org.dcache.chimera.nfs.ChimeraNFSException;

import org.dcache.chimera.IOHimeraFsException;
import org.dcache.chimera.nfs.v4.xdr.nfs_resop4;
import org.dcache.chimera.nfs.vfs.Inode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationWRITE extends AbstractNFSv4Operation {

    private static final Logger _log = LoggerFactory.getLogger(OperationWRITE.class);

    public OperationWRITE(nfs_argop4 args) {
        super(args, nfs_opnum4.OP_WRITE);
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException, IOException {

        final WRITE4res res = result.opwrite;

        if (_args.opwrite.offset.value.value + _args.opwrite.data.remaining() > 0x3ffffffe) {
            throw new ChimeraNFSException(nfsstat.NFSERR_INVAL, "Arbitrary value");
        }


        if (context.currentInode().type() == Inode.Type.DIRECTORY) {
            throw new ChimeraNFSException(nfsstat.NFSERR_ISDIR, "path is a directory");
        }

        if (context.currentInode().type() == Inode.Type.SYMLINK) {
            throw new ChimeraNFSException(nfsstat.NFSERR_INVAL, "path is a symlink");
        }

        if (context.getMinorversion() > 0) {
            context.getSession().getClient().updateLeaseTime();
        } else {
            context.getStateHandler().updateClientLeaseTime(_args.opwrite.stateid);
        }

        long offset = _args.opwrite.offset.value.value;
        int count = _args.opwrite.data.remaining();
        byte[] data = new byte[count];
        _args.opwrite.data.get(data);

        int bytesWritten = context.getFs().write(context.currentInode(),
                data, offset, count);

        if (bytesWritten < 0) {
            throw new IOHimeraFsException("IO not allowed");
        }

        res.status = nfsstat.NFS_OK;
        res.resok4 = new WRITE4resok();
        res.resok4.count = new count4(new uint32_t(bytesWritten));
        res.resok4.committed = stable_how4.FILE_SYNC4;
        res.resok4.writeverf = new verifier4();
        res.resok4.writeverf.value = new byte[nfs4_prot.NFS4_VERIFIER_SIZE];

    }
}
