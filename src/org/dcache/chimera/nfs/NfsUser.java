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
package org.dcache.chimera.nfs;

import com.google.common.collect.Sets;
import java.util.Collections;
import javax.security.auth.Subject;
import org.dcache.auth.GidPrincipal;
import org.dcache.chimera.posix.UnixUser;
import org.dcache.xdr.RpcCall;
import org.dcache.auth.Subjects;
import org.dcache.auth.UidPrincipal;

/**
 * Utility class extract user record from NFS request
 */
public class NfsUser {

    public final static int NOBODY = 65534;

    public final static Subject NFS_NOBODY = new Subject(true,
            Sets.newHashSet(
            new UidPrincipal(NfsUser.NOBODY),
            new GidPrincipal(NfsUser.NOBODY, true)),
            Collections.EMPTY_SET, Collections.EMPTY_SET);

    /*no instances allowed*/
    private NfsUser() {
    }

    public static UnixUser remoteUser(RpcCall call, ExportFile exports) {

        UnixUser user;
        int uid;
        int gid;
        int[] gids;

        Subject subject = call.getCredential().getSubject();
        uid = (int)Subjects.getUid(subject);
        gids = from(Subjects.getGids(subject));
        gid = gids.length > 0 ? gids[0] : NOBODY;

        String host = call.getTransport().getRemoteSocketAddress().getAddress().getHostAddress();

        // root access only for trusted hosts
        if (uid == 0) {
            if ((exports == null) || !exports.isTrusted(
                    call.getTransport().getRemoteSocketAddress().getAddress())) {

                // FIXME: actual 'nobody' account should be used
                uid = NOBODY;
                gid = NOBODY;
            }
        }

        user = new UnixUser(uid, gid, gids, host);

        return user;
    }

    private static int[] from(long[] longs) {
        int[] ints = new int[longs.length];

        int i = 0;
        for (long l : longs) {
            ints[i] = (int) l;
            i++;
        }
        return ints;
    }

}
