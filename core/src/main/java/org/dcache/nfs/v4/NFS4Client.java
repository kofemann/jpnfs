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

/**
 *  with great help of William A.(Andy) Adamson
 */
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.v4.xdr.stateid4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.dcache.nfs.status.BadSeqidException;
import org.dcache.nfs.status.BadStateidException;
import org.dcache.nfs.status.CompleteAlreadyException;
import org.dcache.nfs.status.ExpiredException;
import org.dcache.nfs.status.ResourceException;
import org.dcache.nfs.status.SeqMisorderedException;
import org.dcache.nfs.v4.xdr.seqid4;
import org.dcache.nfs.v4.xdr.verifier4;
import org.dcache.utils.Bytes;

public class NFS4Client {

    private static final Logger _log = LoggerFactory.getLogger(NFS4Client.class);

    private final AtomicInteger _stateIdCounter = new AtomicInteger(0);

    /*
     * from NFSv4.1 spec:
     *
     *
     *  A server's client record is a 5-tuple:
     *
     *   1. co_ownerid
     *          The client identifier string, from the eia_clientowner structure
     *          of the EXCHANGE_ID4args structure
     *   2. co_verifier:
     *          A client-specific value used to indicate reboots, from
     *          the eia_clientowner structure of the EXCHANGE_ID4args structure
     *   3. principal:
     *         The RPCSEC_GSS principal sent via the RPC headers
     *   4. client ID:
     *          The shorthand client identifier, generated by the server and
     *          returned via the eir_clientid field in the EXCHANGE_ID4resok structure
     *   5. confirmed:
     *          A private field on the server indicating whether or not a client
     *          record has been confirmed. A client record is confirmed if there
     *          has been a successful CREATE_SESSION operation to confirm it.
     *          Otherwise it is unconfirmed. An unconfirmed record is established
     *          by a EXCHANGE_ID call. Any unconfirmed record that is not confirmed
     *          within a lease period may be removed.
     *
     */

    /**
     * The client identifier string, from the eia_clientowner structure
     * of the EXCHANGE_ID4args structure
     */
    private final byte[] _ownerId;

    /**
     * A client-specific value used to indicate reboots, from
     * the eia_clientowner structure of the EXCHANGE_ID4args structure
     */

    private final verifier4 _verifier;
    /**
     * The RPCSEC_GSS principal sent via the RPC headers.
     */
    private final Principal _principal;
    /**
     * Client id generated by the server.
     */
    private final long _clientId;

    /**
     * A flag to indicate whether or not a client record has been confirmed.
     */
    private boolean _isConfirmed = false;

    // per client wide unique counter of client stateid requests
    // generated by client, incremented by server
    private int _openStateId = 1;

    /* per client sequence to serialize opens with nfsv4.0 */
    private seqid4 _openSeq;

    /**
     * The sequence number used to track session creations.
     */
    private int _sessionSequence = 1;

    private final Map<stateid4, NFS4State> _clientStates = new ConcurrentHashMap<>();

    // FIXME: the max stateids have to be controlled by session
    private final int MAX_OPEN_STATES = 16384;

    /**
     * sessions associated with the client
     */
    private final Map<Integer, NFSv41Session> _sessions = new HashMap<>();
    private long _cl_time = System.currentTimeMillis();        // time of last lease renewal

    /*

    Client identification is encapsulated in the following structure:

    struct nfs_client_id4 {
        verifier4     verifier;
        opaque        id<NFS4_OPAQUE_LIMIT>;
    };

    The first field, verifier is a client incarnation verifier that is
    used to detect client reboots.  Only if the verifier is different
    from that which the server has previously recorded the client (as
    identified by the second field of the structure, id) does the server
    start the process of canceling the client's leased state.

    The second field, id is a variable length string that uniquely
    defines the client.

     */

    /**
     * Client's {@link InetSocketAddress} seen by server.
     */
    private final InetSocketAddress _clientAddress;
    /**
     * Server's {@link InetSocketAddress} seen by client;
     */
    private final InetSocketAddress _localAddress;
    private ClientCB _cl_cb = null; /* callback info */

    /**
     * lease expiration time in milliseconds.
     */
    private final long _leaseTime;

    private boolean _disposed;

    /**
     * A flag to indicate that the client already have
     * reclaimed associated states.
     */
    private boolean _reclaim_completed;

    /**
     * Indicates that server needs a callback channel with this client.
     */
    private final boolean _callbackNeeded;

    /**
     * Highest NFSv4 minor version supported by the client.
     */
    private final int _minorVersion;

    public NFS4Client(long clientId, int minorVersion, InetSocketAddress clientAddress, InetSocketAddress localAddress,
            byte[] ownerID, verifier4 verifier, Principal principal, long leaseTime, boolean calbackNeeded) {

        _ownerId = ownerID;
        _verifier = verifier;
        _principal = principal;
        _clientId = clientId;

        _clientAddress = clientAddress;
        _localAddress = localAddress;
        _leaseTime = leaseTime;
	_reclaim_completed = false;
        _callbackNeeded = calbackNeeded;
        _minorVersion = minorVersion;
        _log.debug("New client: {}", this);
    }

    public void setCB(ClientCB cb) {
        _cl_cb = cb;
    }

    public ClientCB getCB() {
        return _cl_cb;
    }

    /**
     * Returns the highest NFSv4 minor version number supported by the client.
     */
    public int getMinorVersion() {
        return _minorVersion;
    }

    /**
     * Check whatever client belongs to the provider owner.
     * @param other client owner to test.
     * @return <tt>true</tt> iff client belongs to the provider owner.
     */
    public boolean isOwner(byte[] other) {
        return Arrays.equals(_ownerId, other);
    }

    /**
     *
     * @return client generated verifier
     */
    public verifier4 verifier() {
        return _verifier;
    }

    /**
     *
     * @return client id generated by server
     */
    public long getId() {
        return _clientId;
    }

    public boolean verifierEquals(verifier4 verifier) {
        return _verifier.equals(verifier);
    }

    public synchronized boolean isConfirmed() {
        return _isConfirmed;
    }

    public synchronized void setConfirmed() {
        _isConfirmed = true;
    }

    public synchronized boolean isLeaseValid() {
        return (System.currentTimeMillis() - _cl_time) < _leaseTime;
    }

    /**
     * Update client's lease time if it not expired.
     *
     * @throws ExpiredException if difference between current time and last
     * lease more than max_lease_time
     */
    public synchronized void updateLeaseTime() throws ChimeraNFSException {

        long curentTime = System.currentTimeMillis();
        long delta = curentTime - _cl_time;
        if (delta > _leaseTime) {
            _clientStates.clear();
            throw new ExpiredException("lease time expired: (" + delta +"): " + Bytes.toHexString(_ownerId) +
                    " (" + _clientId + ").");
        }
        _cl_time = curentTime;
    }

    /**
     * sets client lease time with current time
     */
    public synchronized void refreshLeaseTime() {
        _cl_time = System.currentTimeMillis();
    }

    /**
     * re-initialize client
     */
    public synchronized void reset() {
        refreshLeaseTime();
        _isConfirmed = false;
        _openSeq = null;
    }

    /**
     * Get the client's {@link InetSocketAddress} seen by server.
     * @return client's address
     */
    public InetSocketAddress getRemoteAddress() {
        return _clientAddress;
    }

    /**
     * Get server's {@link InetSocketAddress} seen by the client.
     * @return server's address
     */
    public InetSocketAddress getLocalAddress() {
        return _localAddress;
    }

    public int currentSeqID() {
        return _sessionSequence;
    }

    public NFS4State createState() throws ChimeraNFSException {
        if (_clientStates.size() >= MAX_OPEN_STATES) {
            throw new ResourceException("Too many states.");
        }

        NFS4State state = new NFS4State(generateNewState(), _openStateId);
        _openStateId++;
        _clientStates.put(state.stateid(), state);
        return state;
    }

    public synchronized void validateOpenSequence(seqid4 openSeqid) throws BadSeqidException {
        if (_openSeq == null) {
            _openSeq = openSeqid;
        } else {
            int next = _openSeq.value + 1;
            if (next != openSeqid.value) {
                throw new BadSeqidException();
            }
            _openSeq.value = next;
        }
    }

    public void releaseState(stateid4 stateid) throws ChimeraNFSException {
        NFS4State state = _clientStates.remove(stateid);
        if (state == null) {
            throw new BadStateidException("State not known to the client: " + stateid);
        }
        state.tryDispose();
    }

    public NFS4State state(stateid4 stateid) throws ChimeraNFSException {
        NFS4State state = _clientStates.get(stateid);
        if(state == null) {
            throw new BadStateidException("State not known to the client: " + stateid);
        }
        return state;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(_clientAddress).append(":")
                .append(Bytes.toHexString(_ownerId))
                .append("@")
                .append(_clientId)
                .append(":v4.").append(getMinorVersion());
        return sb.toString();
    }

    /**
     *
     * @return list of sessions created by client.
     */
    public Collection<NFSv41Session> sessions() {
        return _sessions.values();
    }

    public synchronized NFSv41Session createSession(int sequence, int cacheSize, int cbCacheSize, int maxOps, int maxCbOps) throws ChimeraNFSException {

        /*
         * For unconfirmed cleints server expects sequence number to be equal to
         * value of eir_sequenceid that was returned in results of the EXCHANGE_ID.
         */
        _log.debug("session for sequience: {}", sequence);
        if (sequence > _sessionSequence && _isConfirmed) {
            throw new SeqMisorderedException("bad sequence id: " + _sessionSequence + " / " + sequence);
        }

        if (sequence == _sessionSequence - 1 && !_isConfirmed) {
            throw new SeqMisorderedException("bad sequence id: " + _sessionSequence + " / " + sequence);
        }

        if (sequence == _sessionSequence - 1) {
            _log.debug("Retransmit on create session detected");
            return _sessions.get(sequence);
        }

        if (sequence != _sessionSequence ) {
            throw new SeqMisorderedException("bad sequence id: " + _sessionSequence + " / " + sequence);
        }

        NFSv41Session session = new NFSv41Session(this, _sessionSequence, cacheSize, cbCacheSize, maxOps, maxCbOps);
        _sessions.put(_sessionSequence, session);
        _sessionSequence++;

        if(!_isConfirmed){
            _isConfirmed = true;
            _log.debug("set client confirmed");
        }

        return session;
    }

    public void removeSession(NFSv41Session session) {
        _sessions.remove(session.getSequence());
    }

    /**
     * Tell if there are any sessions owned by the client.
     *
     * @return true if client has at least one session.
     */
    public boolean hasSessions() {
        return !_sessions.isEmpty();
    }

    public Principal principal() {
        return _principal;
    }

    public boolean hasState() {
        return !_clientStates.isEmpty();
    }

    /**
     * Attach the state to the client.
     *
     * @param state to attach
     */
    public void attachState(NFS4State state) {
        _clientStates.put(state.stateid(), state);
    }

    /**
     * Detach a state from the client.
     *
     * @param state to detach
     */
    public void detachState(NFS4State state) {
        _clientStates.remove(state.stateid());
    }

    /**
     * Release resources used by this client if not released yet. Any subsequent
     * call will have no effect.
     */
    public final void tryDispose() {
        if (!_disposed) {
            dispose();
            _disposed = true;
        }
    }

    /**
     * Release resources used by this client.
     */
    protected void dispose() {
        _clientStates.values().forEach((state) -> {
            state.tryDispose();
        });
    }

    public synchronized void reclaimComplete() throws ChimeraNFSException {
	if (_reclaim_completed) {
	    throw new CompleteAlreadyException("Duplicating reclaim");
	}
	_reclaim_completed = true;
    }

    public synchronized boolean needReclaim() {
	return !_reclaim_completed;
    }

    public boolean isCallbackNeede() {
        return _callbackNeeded;
    }

    /*
     * we construct 'other' fileld of state IDs as following:
     * |0 -  7| : client id
     * |8 - 11| : clients state counter
     */
    private byte[] generateNewState() {
        byte[] other = new byte[12];
        Bytes.putLong(other, 0, _clientId);
        Bytes.putInt(other, 8, _stateIdCounter.incrementAndGet());
        return other;
    }
}
