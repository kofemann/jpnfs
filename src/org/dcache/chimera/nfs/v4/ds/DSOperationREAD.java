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
package org.dcache.chimera.nfs.v4.ds;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.dcache.chimera.nfs.v4.AbstractNFSv4Operation;
import org.dcache.chimera.nfs.v4.CompoundContext;
import org.dcache.chimera.nfs.ChimeraNFSException;
import org.dcache.chimera.nfs.v4.xdr.READ4res;
import org.dcache.chimera.nfs.v4.xdr.READ4resok;
import org.dcache.chimera.nfs.v4.xdr.nfs_argop4;
import org.dcache.chimera.nfs.v4.xdr.nfs_opnum4;
import org.dcache.chimera.nfs.v4.xdr.nfs_resop4;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.vfs.FsCache;
import org.dcache.chimera.posix.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DSOperationREAD extends AbstractNFSv4Operation {

    private static final Logger _log = LoggerFactory.getLogger(DSOperationREAD.class);
     private final FsCache _fsCache;

    public DSOperationREAD(nfs_argop4 args, FsCache fsCache) {
        super(args, nfs_opnum4.OP_READ);
        _fsCache = fsCache;
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException, IOException {
        final READ4res res = result.opread;

        Stat inodeStat = context.getFs().getattr(context.currentInode());
        boolean eof = false;

        long offset = _args.opread.offset.value.value;
        int count = _args.opread.count.value.value;

        ByteBuffer bb = ByteBuffer.allocateDirect(count);

        FileChannel in = _fsCache.get(context.currentInode());

        int bytesReaded = in.read(bb, offset);
        if (bytesReaded < 0) {
            eof = true;
            bytesReaded = 0;
        }

        res.status = nfsstat.NFS_OK;
        res.resok4 = new READ4resok();
        res.resok4.data = bb;

        if (offset + bytesReaded == inodeStat.getSize()) {
            eof = true;
        }
        res.resok4.eof = eof;

        _log.debug("MOVER: {}@{} readed, {} requested.",
                new Object[]{bytesReaded, offset, _args.opread.count.value.value});
    }
}
