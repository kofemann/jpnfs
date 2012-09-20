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
import org.dcache.chimera.nfs.v4.xdr.rpcsec_gss_info;
import org.dcache.chimera.nfs.v4.xdr.nfs_argop4;
import org.dcache.chimera.nfs.v4.xdr.secinfo4;
import org.dcache.chimera.nfs.v4.xdr.nfs_opnum4;
import org.dcache.chimera.nfs.v4.xdr.SECINFO4resok;
import org.dcache.chimera.nfs.v4.xdr.SECINFO4res;
import org.dcache.chimera.nfs.ChimeraNFSException;
import org.dcache.chimera.nfs.v4.xdr.nfs_resop4;
import org.dcache.chimera.nfs.vfs.Inode;
import org.dcache.chimera.nfs.vfs.Stat;
import org.dcache.xdr.RpcAuthType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationSECINFO extends AbstractNFSv4Operation {

    private static final Logger _log = LoggerFactory.getLogger(OperationSECINFO.class);

    OperationSECINFO(nfs_argop4 args) {
        super(args, nfs_opnum4.OP_SECINFO);
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException, IOException {

        final SECINFO4res res = result.opsecinfo;
        Stat stat = context.getFs().getattr(context.currentInode());

        if (stat.type() != Stat.Type.DIRECTORY) {
            throw new ChimeraNFSException(nfsstat.NFSERR_NOTDIR, "not a directory");
        }

        res.resok4 = new SECINFO4resok();
        res.resok4.value = new secinfo4[1];

        res.resok4.value[0] = new secinfo4();
        res.resok4.value[0].flavor = RpcAuthType.UNIX;
        res.resok4.value[0].flavor_info = new rpcsec_gss_info();
        context.clearCurrentInode();
        res.status = nfsstat.NFS_OK;
    }
}
