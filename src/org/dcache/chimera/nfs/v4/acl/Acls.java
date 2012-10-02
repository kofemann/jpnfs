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
package org.dcache.chimera.nfs.v4.acl;

import java.util.Arrays;
import org.dcache.chimera.nfs.v4.xdr.*;

/**
 * Utility class to calculate unix permission mask from NFSv4 ACL and vise versa.
 *
 * Based on rfc 5661.
 */
public class Acls {

    /*
     * unix permission bits offset as defined in POSIX
     * for st_mode filed of the stat  structure.
     */
    private static final int BIT_MASK_OWNER_OFFSET = 6;
    private static final int BIT_MASK_GROUP_OFFSET = 3;
    private static final int BIT_MASK_OTHER_OFFSET = 0;

    static public final int RBIT = 04; // read bit
    static public final int WBIT = 02; // write bit
    static public final int XBIT = 01; //execute bit

    public final static utf8str_mixed OWNER =    new utf8str_mixed("OWNER@");
    public final static utf8str_mixed GROUP =    new utf8str_mixed("GROUP@");
    public final static utf8str_mixed EVERYONE = new utf8str_mixed("EVERYONE@");

    private final static aceflag4 NO_FLAGS = new aceflag4(new uint32_t(0));
    private final static acetype4 ALLOW = new acetype4(new uint32_t(nfs4_prot.ACE4_ACCESS_ALLOWED_ACE_TYPE));
    private final static acetype4 DENY = new acetype4(new uint32_t(nfs4_prot.ACE4_ACCESS_DENIED_ACE_TYPE));

    private Acls() {}

    /**
     * Add access control list to specified access control entry.
     * Effectively, creates a new ACL where specified ace is added to the beginning
     * of provided acl
     *
     * @param ace to add to
     * @param acl to append
     * @return newly constructed acl
     */
    public static nfsace4[] addACE(nfsace4 ace, nfsace4[] acl) {
        nfsace4[] newAcl = new nfsace4[acl.length + 1];
        newAcl[0] = ace;
        System.arraycopy(acl, 0, newAcl, 1, acl.length);
        return newAcl;
    }

    /**
     * Add specified access control entry to access control list.
     * Effectively, creates a new ACL where specified ace is added to the end
     * of provided acl
     *
     * @param acl to add to
     * @param ace to append
     * @return newly constructed acl
     */
    public static nfsace4[] addACE(nfsace4[] acl, nfsace4 ace) {
        nfsace4[] newAcl = new nfsace4[acl.length + 1];
        System.arraycopy(acl, 0, newAcl, 0, acl.length);
        newAcl[acl.length] = ace;
        return newAcl;
    }

    /**
     * Calculate access control list from provided unix permission mask.
     *
     * @param mode to calculate from
     * @param isDir {@code true} if acl have to be calculated for a directory
     * @return acl
     */
    public static nfsace4[] of(int mode, boolean isDir) {

        acemask4 ownerMask = toAceMask(mode >> BIT_MASK_OWNER_OFFSET, isDir, true);
        acemask4 groupMask = toAceMask(mode >> BIT_MASK_GROUP_OFFSET, isDir, false);
        acemask4 everyoneMask = toAceMask(mode, isDir, false);

        nfsace4 ownerAceAllow = new nfsace4();
        ownerAceAllow.access_mask = ownerMask;
        ownerAceAllow.who = OWNER;
        ownerAceAllow.type = ALLOW;
        ownerAceAllow.flag = NO_FLAGS;

        nfsace4 ownerAceDeny = new nfsace4();
        ownerAceDeny.access_mask = acemask4.clear(ownerMask, acemask4.allOf(groupMask, everyoneMask));
        ownerAceDeny.who = OWNER;
        ownerAceDeny.type = DENY;
        ownerAceDeny.flag = NO_FLAGS;

        nfsace4 groupAceAllow = new nfsace4();
        groupAceAllow.access_mask = groupMask;
        groupAceAllow.who = GROUP;
        groupAceAllow.type = ALLOW;
        groupAceAllow.flag = NO_FLAGS;

        nfsace4 groupAceDeny = new nfsace4();
        groupAceDeny.access_mask = acemask4.clear(groupMask, everyoneMask);
        groupAceDeny.who = GROUP;
        groupAceDeny.type = DENY;
        groupAceDeny.flag = NO_FLAGS;

        nfsace4 otherAceAllow = new nfsace4();
        otherAceAllow.access_mask = everyoneMask;
        otherAceAllow.who = EVERYONE;
        otherAceAllow.type = ALLOW;
        otherAceAllow.flag = NO_FLAGS;

        return compact( new nfsace4[] {
            ownerAceAllow,
            ownerAceDeny,
            groupAceAllow,
            groupAceDeny,
            otherAceAllow
        });
    }

    private static acemask4 toAceMask(int mode, boolean isDir, boolean isOwner) {

        int mask = 0;

        if (isOwner) {
            mask |= nfs4_prot.ACE4_WRITE_ACL
                    | nfs4_prot.ACE4_WRITE_ATTRIBUTES;
        }

        if( (mode &  RBIT) != 0) {
            mask |= nfs4_prot.ACE4_READ_DATA |
                    nfs4_prot.ACE4_READ_ACL |
                    nfs4_prot.ACE4_READ_ATTRIBUTES;
        }

        if ((mode & WBIT) != 0) {
            mask |= nfs4_prot.ACE4_WRITE_DATA |
                    nfs4_prot.ACE4_APPEND_DATA;

            if(isDir)
                mask |= nfs4_prot.ACE4_DELETE_CHILD;
        }

        if ((mode & XBIT) != 0) {
            if(isDir)
                mask |= nfs4_prot.ACE4_LIST_DIRECTORY;

            mask |= nfs4_prot.ACE4_EXECUTE;

            if (isDir)
                mask |= nfs4_prot.ACE4_LIST_DIRECTORY;
        }

        acemask4 acemask = new acemask4();
        acemask.value = new uint32_t(mask);

        return acemask;
    }

    /**
     * Calculate unix permissions mode from provided access control list.
     * Only aces for {@code OWNER@}, {@code GROUP@} and {@code EVERYONE@} is considered.
     *
     * @param acl to evaluate
     * @return mode
     */
    @SuppressWarnings("PointlessBitwiseExpression")
    public static int toMode(nfsace4[] acl) {
        return calculateBits(OWNER, acl) << BIT_MASK_OWNER_OFFSET
                | calculateBits(GROUP, acl) << BIT_MASK_GROUP_OFFSET
                | calculateBits(EVERYONE, acl) << BIT_MASK_OTHER_OFFSET;
    }

    /**
     * Adjust @{code acl} to changes from {@code mode}.
     *
     * @param acl
     * @param mode
     * @return new acls
     */
    public static nfsace4[] adjust(nfsace4[] acl, int mode) {

        nfsace4[] newAcl = new nfsace4[acl.length];
        int i = 0;
        for(nfsace4 ace: acl) {
            if( !isSpecialPrincipal(ace.who) ) {
                newAcl[i] = ace;
                i++;
            }
        }
        return Arrays.copyOf(newAcl, i);
    }

    private static int getBitR(int acemask) {
        if( (acemask & nfs4_prot.ACE4_READ_DATA) != 0)
          return RBIT;
        return 0;
    }

    private static int getBitW(int acemask) {
        if((acemask & (nfs4_prot.ACE4_WRITE_DATA |
                    nfs4_prot.ACE4_APPEND_DATA)) != 0 )
          return WBIT;
        return 0;
    }

    private static int getBitX(int acemask) {
      if ((acemask & nfs4_prot.ACE4_EXECUTE) != 0)
        return XBIT;
      return 0;
    }

    /*
     * calculate unix permission bits for the specified principal
     */
    private static int calculateBits(utf8str_mixed principal, nfsace4[] acl) {
        int mode = 0;
        /*
         * walk acl in reverse order and apply masks for OWNER and EVERYONE
         */
        for(int i = acl.length -1; i >= 0; i--) {
            final nfsace4 ace = acl[i];

            /*
             * consider only ALLOWED and DENIED aces
             */
            if (ace.type.value.value != nfs4_prot.ACE4_ACCESS_DENIED_ACE_TYPE
                    && ace.type.value.value != nfs4_prot.ACE4_ACCESS_ALLOWED_ACE_TYPE) {
                continue;
            }

            if( ace.who.equals(EVERYONE) || ace.who.equals(principal)) {
                int acemask = ace.access_mask.value.value;
                int rwx = getBitR(acemask) |  getBitW(acemask) | getBitX(acemask);
                if (ace.type.value.value == nfs4_prot.ACE4_ACCESS_ALLOWED_ACE_TYPE) {
                    mode |= rwx;
                } else {
                    mode ^= rwx;
                }
            }
        }
        return mode;
    }

    /**
     * Compact {@code acl} by removing {@link nfsace4} with empty access mask.
     *
     * @param acl to compact
     * @return compacted acl
     */
    public static nfsace4[] compact(nfsace4[] acl) {
        int size = acl.length;
        for (nfsace4 ace : acl) {
            if (ace.access_mask.value.value == 0) {
                size--;
            }
        }

        if (size == acl.length) {
            return acl;
        }

        nfsace4[] compact = new nfsace4[size];
        int i = 0;
        for (nfsace4 ace : acl) {
            if (ace.access_mask.value.value != 0) {
                compact[i] = ace;
                i++;
            }
        }

        return compact;
    }

    private static boolean isSpecialPrincipal(utf8str_mixed who) {
        String w = who.toString();
        return w.charAt(w.length() -1) == '@';
    }
}