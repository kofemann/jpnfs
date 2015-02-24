/*
 * Copyright (c) 2009 - 2014 Deutsches Elektronen-Synchroton,
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

import java.io.IOException;
import org.dcache.nfs.nfsstat;
import org.dcache.nfs.v4.xdr.nfs_argop4;
import org.dcache.nfs.v4.xdr.change_info4;
import org.dcache.nfs.v4.xdr.changeid4;
import org.dcache.nfs.v4.xdr.nfs_opnum4;
import org.dcache.nfs.v4.xdr.RENAME4res;
import org.dcache.nfs.v4.xdr.RENAME4resok;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.status.NotDirException;
import org.dcache.nfs.v4.xdr.nfs_resop4;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationRENAME extends AbstractNFSv4Operation {

    private static final Logger _log = LoggerFactory.getLogger(OperationRENAME.class);

    OperationRENAME(nfs_argop4 args) {
        super(args, nfs_opnum4.OP_RENAME);
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException, IOException {
        final RENAME4res res = result.oprename;

        Inode sourceDir = context.savedInode();
        Inode destDir = context.currentInode();

        Stat sourceStat = context.getFs().getattr(sourceDir);
        Stat destStat = context.getFs().getattr(destDir);

        if (sourceStat.type() != Stat.Type.DIRECTORY) {
            throw new NotDirException("source path not a directory");
        }

        if (destStat.type() != Stat.Type.DIRECTORY) {
            throw new NotDirException("destination path  not a directory");
        }

        String oldName = NameFilter.convertName(_args.oprename.oldname.value, context.getConfig().getMaxFileName());
        String newName = NameFilter.convertName(_args.oprename.newname.value, context.getConfig().getMaxFileName());

        _log.debug("Rename: src={} name={} dest={} name={}",
                sourceDir,
                oldName,
                destDir,
                newName);

        boolean isChanged = context.getFs().move(sourceDir, oldName, destDir, newName);
        long now = 0;
        if (isChanged) {
            now = System.currentTimeMillis();
        }

        res.resok4 = new RENAME4resok();

        res.resok4.source_cinfo = new change_info4();
        res.resok4.source_cinfo.atomic = true;
        res.resok4.source_cinfo.before = new changeid4(sourceStat.getCTime());
        res.resok4.source_cinfo.after = new changeid4(isChanged ? now : sourceStat.getCTime());

        res.resok4.target_cinfo = new change_info4();
        res.resok4.target_cinfo.atomic = true;
        res.resok4.target_cinfo.before = new changeid4(destStat.getCTime());
        res.resok4.target_cinfo.after = new changeid4(isChanged ? now : destStat.getCTime());

        res.status = nfsstat.NFS_OK;
    }
}
