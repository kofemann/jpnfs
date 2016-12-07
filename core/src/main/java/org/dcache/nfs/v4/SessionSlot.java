/*
 * Copyright (c) 2009 - 2016 Deutsches Elektronen-Synchroton,
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.status.DelayException;
import org.dcache.nfs.v4.xdr.nfs_resop4;
import org.dcache.nfs.status.RetryUncacheRepException;
import org.dcache.nfs.status.SeqMisorderedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class SessionSlot {

    private static final Logger _log = LoggerFactory.getLogger(SessionSlot.class);

    private int _sequence;
    private List<nfs_resop4> _reply;

    /**
     * Indicate that current slot is in use by a request.
     */
    private AtomicBoolean _inUse = new AtomicBoolean(false);

    public SessionSlot() {
       _sequence = 0;
    }

    /**
     *
     * @param sequence
     * @param reply
     * @return true if retransmit is detected and cached reply available.
     * @throws ChimeraNFSException
     */
    public List<nfs_resop4> checkSlotSequence(int sequence, boolean checkCache) throws ChimeraNFSException {

        if( sequence == _sequence ) {

            _log.info("retransmit detected");
            if( _reply != null ) {
                return _reply;
            }

            if(checkCache)
                throw new RetryUncacheRepException();
            return null;
        }

        int validValue = _sequence + 1;
        if (sequence != validValue) {
            throw new SeqMisorderedException("disordered : v/n : " + Integer.toHexString(validValue) +
                    "/" + Integer.toHexString(sequence));
        }

        _reply = null;
        return null;
    }

    void update(List<nfs_resop4> reply) {
        _reply = reply;
    }

    /**
     * Acquires an exclusive access to this session slot.
     * @throws DelayException if session slot is in use.
     */
    public void acquire() throws DelayException {
        if (!_inUse.compareAndSet(false, true)) {
            throw new DelayException("slot is in use");
        }
    }

    /**
     * Releases this session slot.
     */
    public void release() {
        _inUse.set(true);
    }

    public void releaseAndIncrease() {
        _sequence ++;
        release();
    }

}
