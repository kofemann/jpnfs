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
package org.dcache.nfs.v4.ds;

import java.io.IOException;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.nfsstat;
import org.dcache.nfs.v4.AbstractNFSv4Operation;
import org.dcache.nfs.v4.CompoundContext;
import org.dcache.nfs.v4.xdr.COMMIT4res;
import org.dcache.nfs.v4.xdr.COMMIT4resok;
import org.dcache.nfs.v4.xdr.nfs4_prot;
import org.dcache.nfs.v4.xdr.nfs_argop4;
import org.dcache.nfs.v4.xdr.nfs_opnum4;
import org.dcache.nfs.v4.xdr.nfs_resop4;
import org.dcache.nfs.v4.xdr.verifier4;
import org.dcache.nfs.vfs.FsCache;
import java.nio.channels.FileChannel;
import org.dcache.nfs.vfs.Stat;
import org.dcache.xdr.OncRpcException;

public class DSOperationCOMMIT extends AbstractNFSv4Operation {

    private final FsCache _fsCache;

    public DSOperationCOMMIT(nfs_argop4 args, FsCache fsCache) {
        super(args, nfs_opnum4.OP_COMMIT);
        _fsCache = fsCache;
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException, IOException, OncRpcException {
        // FIXME: sync the data

        final COMMIT4res res = result.opcommit;
        if (context.getFs() != null) {
            FileChannel out = _fsCache.get(context.currentInode());
            Stat stat = context.getFs().getattr(context.currentInode());
            stat.setSize(out.size());
            context.getFs().setattr(context.currentInode(), stat);
        }

        res.status = nfsstat.NFS_OK;
        res.resok4 = new COMMIT4resok();
        res.resok4.writeverf = new verifier4();
        res.resok4.writeverf.value = new byte[nfs4_prot.NFS4_VERIFIER_SIZE];
    }
}
