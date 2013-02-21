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

import java.util.List;
import org.dcache.chimera.nfs.ChimeraNFSException;
import org.dcache.chimera.nfs.v4.xdr.nfs4_prot;
import org.dcache.chimera.nfs.v4.xdr.sessionid4;
import org.dcache.chimera.nfs.v4.xdr.nfs_resop4;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.utils.Bytes;

public class NFSv41Session {

    /**
     * Unique session identifier. 16 bytes long.
     *
     * |0 - client id - 7|8 - reserved - 11 | 12 - sequence id - 15|
     */
    private final sessionid4 _session;
    /**
     * Session reply slots.
     */
    private final SessionSlot[] _slots;
    private final NFS4Client _client;

    private final int _sequence;

    public NFSv41Session(NFS4Client client, int sequence, int replyCacheSize) {
        _client = client;
        _sequence = sequence;
        _slots = new SessionSlot[replyCacheSize];
        byte[] id  = new byte[nfs4_prot.NFS4_SESSIONID_SIZE];

        Bytes.putLong(id, 0, client.getId());
        Bytes.putInt(id, 12, sequence);
        _session = new sessionid4(id);
    }

    public sessionid4 id() {
        return _session;
    }

    public NFS4Client getClient() {
        return _client;
    }

    /**
     * Get maximum slot id.
     * @return max slot id.
     */
    public int getHighestSlot() {
        return _slots.length - 1;
    }

    /**
     * Get highest slot id used.
     * @return slot id or -1 if there are no sloths have been used yet
     */
    public int getHighestUsedSlot() {
        int id;
        for(id = getHighestSlot(); id >= 0 && _slots[id] == null; id--) {
           /*
            * NOP. We only move pointer
            */
        }
        return id;
    }

    public List<nfs_resop4> checkCacheSlot(int slot, int sequence, boolean checkCache)
            throws ChimeraNFSException {
        return getSlot(slot).checkSlotSequence(sequence, checkCache);
    }

    /**
     * Get cache slot for given id.
     * @param i
     * @return cache slot.
     * @throws ChimeraNFSException
     */
    private SessionSlot getSlot(int slot) throws ChimeraNFSException {

        if (slot < 0 || slot > getHighestSlot()) {
            throw new ChimeraNFSException(nfsstat.NFSERR_BADSLOT, "slot id overflow");
        }

        if (_slots[slot] == null) {
            _slots[slot] = new SessionSlot();
        }

        return _slots[slot];
    }

    public int getSequence() {
        return _sequence;
    }

    @Override
    public String toString() {
        return _client.getRemoteAddress() + " : " +toHexString(_session.value);
    }

    public String toHexString(byte[] data) {

        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    public void updateSlotCache(int slot, List<nfs_resop4> reply) throws ChimeraNFSException {
        getSlot(slot).update(reply);
    }
}
