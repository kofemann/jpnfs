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
package org.dcache.chimera.nfs.v4.client;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.net.HostAndPort;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import jline.ArgumentCompletor;
import jline.ConsoleReader;
import jline.SimpleCompletor;

import org.dcache.chimera.nfs.v4.xdr.COMPOUND4args;
import org.dcache.chimera.nfs.v4.xdr.COMPOUND4res;
import org.dcache.chimera.nfs.ChimeraNFSException;
import org.dcache.chimera.nfs.v4.Stateids;
import org.dcache.chimera.nfs.v4.xdr.clientid4;
import org.dcache.chimera.nfs.v4.xdr.deviceid4;
import org.dcache.chimera.nfs.v4.xdr.entry4;
import org.dcache.chimera.nfs.v4.xdr.fattr4_fs_locations;
import org.dcache.chimera.nfs.v4.xdr.fattr4_type;
import org.dcache.chimera.nfs.v4.xdr.layout4;
import org.dcache.chimera.nfs.v4.xdr.layoutiomode4;
import org.dcache.chimera.nfs.v4.xdr.layouttype4;
import org.dcache.chimera.nfs.v4.xdr.nfs4_prot;
import org.dcache.chimera.nfs.v4.xdr.nfs_fh4;
import org.dcache.chimera.nfs.v4.xdr.nfs_opnum4;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.v4.xdr.mode4;
import org.dcache.chimera.nfs.v4.xdr.nfsv4_1_file_layout4;
import org.dcache.chimera.nfs.v4.xdr.nfsv4_1_file_layout_ds_addr4;
import org.dcache.chimera.nfs.v4.xdr.sequenceid4;
import org.dcache.chimera.nfs.v4.xdr.sessionid4;
import org.dcache.chimera.nfs.v4.xdr.state_protect_how4;
import org.dcache.chimera.nfs.v4.xdr.stateid4;
import org.dcache.chimera.nfs.v4.xdr.uint64_t;
import org.dcache.chimera.nfs.v4.xdr.verifier4;
import org.dcache.chimera.nfs.vfs.Stat;
import org.dcache.utils.Bytes;
import org.dcache.xdr.IpProtocolType;
import org.dcache.xdr.OncRpcException;

public class Main {

    private final nfs4_prot_NFS4_PROGRAM_Client _nfsClient;
    private final Map<deviceid4, FileIoDevice> _knowDevices = new HashMap<deviceid4, FileIoDevice>();
    private nfs_fh4 _cwd = null;
    private nfs_fh4 _rootFh = null;
    // FIXME:
    private nfs_fh4 _ioFH = null;
    private clientid4 _clientIdByServer = null;
    private sequenceid4 _sequenceID = null;
    private sessionid4 _sessionid = null;
    private long _lastUpdate = -1;
    private final static long LEASETIME = 10 * 1000;
    private boolean _isMDS = false;
    private boolean _isDS = false;
    private static final String PROMPT = "NFSv41: ";

    public static void main(String[] args) throws IOException, OncRpcException, InterruptedException {

        System.out.println("Started the NFS4 Client ....");
        String line;

        Timer timer = new Timer();
        Main nfsClient = null;

        final String[] commands = {
            "mount",
            "cd",
            "ls",
            "lookup",
            "mkdir",
            "read",
            "readatonce",
            "filebomb",
            "remove",
            "umount",
            "write",
            "fs_locations",
            "getattr"
        };

        PrintWriter out = new PrintWriter(System.out);
        ConsoleReader reader = new ConsoleReader(System.in, out);
        reader.setUseHistory(true);
        List<SimpleCompletor> completors = new LinkedList<SimpleCompletor>();
        completors.add(new SimpleCompletor(commands));
        reader.addCompletor(new ArgumentCompletor(completors));


        if (args.length > 0) {
            HostAndPort hp = HostAndPort.fromString(args[0])
                    .withDefaultPort(2049)
                    .requireBracketsForIPv6();

            InetSocketAddress serverAddress = new InetSocketAddress(hp.getHostText(), hp.getPort());
            nfsClient = new Main(serverAddress);
            nfsClient.mount("/");
        }

        while ((line = reader.readLine(PROMPT)) != null) {
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }

            String[] commandArgs = line.split("[ \t]+");

            if (commandArgs[0].equals("mount")) {

                String host = commandArgs.length > 1 ? commandArgs[1]
                        : "localhost";
                String root =  commandArgs.length > 2 ? commandArgs[2]
                        : "/";
                nfsClient = new Main(InetAddress.getByName(host));
                nfsClient.mount(root);

            } else if (commandArgs[0].equals("umount")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                nfsClient.umount();
                timer.purge();
                nfsClient = null;

            } else if (commandArgs[0].equals("ls")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length == 2) {
                    nfsClient.readdir(commandArgs[1]);
                } else {
                    nfsClient.readdir();
                }

            } else if (commandArgs[0].equals("cd")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: cd <path>");
                    continue;
                }
                nfsClient.cwd(commandArgs[1]);

            } else if (commandArgs[0].equals("lookup")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: lookup <path>");
                    continue;
                }
                nfsClient.lookup(commandArgs[1]);

            } else if (commandArgs[0].equals("getattr")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: getattr <path>");
                    continue;
                }
                nfsClient.getattr(commandArgs[1]);

            }else if (commandArgs[0].equals("mkdir")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: mkdir <path>");
                    continue;
                }
                nfsClient.mkdir(commandArgs[1]);

            } else if (commandArgs[0].equals("read")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: read <file>");
                    continue;
                }
                nfsClient.read(commandArgs[1]);

            } else if (commandArgs[0].equals("readatonce")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: readatonce <file>");
                    continue;
                }
                nfsClient.readatonce(commandArgs[1]);

            } else if (commandArgs[0].equals("fs_locations")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: fs_locations <file>");
                    continue;
                }

                nfsClient.get_fs_locations(commandArgs[1]);

            } else if (commandArgs[0].equals("remove")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: remove <file>");
                    continue;
                }
                nfsClient.remove(commandArgs[1]);

            } else if (commandArgs[0].equals("write")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 3) {
                    System.out.println("usage: write <src> <dest>");
                    continue;
                }
                nfsClient.write(commandArgs[1], commandArgs[2]);

            } else if (commandArgs[0].equals("filebomb")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                if (commandArgs.length != 2) {
                    System.out.println("usage: filebomb <num>");
                    continue;
                }
                nfsClient.filebomb(Integer.parseInt(commandArgs[1]));

            } else if (commandArgs[0].equals("gc")) {

                if (nfsClient == null) {
                    System.out.println("Not mounted");
                    continue;
                }

                nfsClient.gc();

            } else if (line.equalsIgnoreCase("quit")
                    || line.equalsIgnoreCase("exit")) {

                if (nfsClient != null) {
                    nfsClient.destroy_session();
                    nfsClient.destroy_clientid();
                }
                timer.purge();
                System.exit(0);
            } else {
                out.println("Supported commands: ");
                for (String command : commands) {
                    out.println("    " + command);
                }
            }
            out.flush();

            timer.schedule(new LeasUpdater(nfsClient), LEASETIME, LEASETIME);

        }
    }

    /**
     * generate set of files and delete them after words
     * @param string
     * @param string2
     * @throws IOException
     * @throws OncRpcException
     */
    private void filebomb(int count) throws OncRpcException, IOException {

        List<String> files = new ArrayList<String>(count);
        long start = System.currentTimeMillis();
        try {
            for (int i = 0; i < count; i++) {
                String file = UUID.randomUUID().toString();
                write("/etc/profile", file);
                files.add(file);
            }
        } finally {
            for (String file : files) {
                System.out.println("Remove: " + file);
                remove(file);
            }
            System.out.println(count + " files in " + (System.currentTimeMillis() - start) / 1000);
        }

    }

    private void gc() throws OncRpcException, IOException {

        exchange_id();
        create_session();
        sequence();

    }

    private boolean needUpdate() {
        // 60 seconds
        return System.currentTimeMillis() - _lastUpdate > 60000;
    }

    private static class LeasUpdater extends TimerTask {

        private final Main _nfsClient;

        LeasUpdater(Main nfsClient) {
            _nfsClient = nfsClient;
        }

        @Override
        public void run() {
            try {
                if (_nfsClient.needUpdate()) {
                    _nfsClient.sequence();
                }
            } catch (OncRpcException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public Main(InetAddress host) throws OncRpcException, IOException {
        _nfsClient = new nfs4_prot_NFS4_PROGRAM_Client(host, IpProtocolType.TCP);

        _servers.asMap().put(_nfsClient.getTransport().getRemoteSocketAddress(), this);

    }

    public Main(InetSocketAddress address) throws OncRpcException,
            IOException {
        _nfsClient = new nfs4_prot_NFS4_PROGRAM_Client(address.getAddress(),
                address.getPort(), IpProtocolType.TCP);

        _servers.asMap().put(address, this);
    }

    public void mount(String root) throws OncRpcException, IOException {
        exchange_id();
        create_session();

        getRootFh(root);
        get_supported_attributes();
        if (_isMDS) {
            get_devicelist();
        }

        _lastUpdate = System.currentTimeMillis();

    }

    public void dsMount() throws OncRpcException, IOException {
        exchange_id();
        create_session();
        _lastUpdate = System.currentTimeMillis();
    }

    public void umount() throws OncRpcException, IOException {
        destroy_session();
        destroy_clientid();
    }

    private void exchange_id() throws OncRpcException, IOException {

        String domain = "nairi.desy.de";
        String name = "dCache.ORG java based client";

        COMPOUND4args args = new CompoundBuilder()
                .withExchangeId(domain, name, UUID.randomUUID().toString(), 0,state_protect_how4.SP4_NONE )
                .withTag("exchange_id")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            if (compound4res.resarray.get(0).opexchange_id.eir_resok4.eir_server_impl_id.length > 0) {
                String serverId = compound4res.resarray.get(0).opexchange_id.eir_resok4.eir_server_impl_id[0].nii_name.toString();
                System.out.println("Connected to: " + serverId);
            } else {
                System.out.println("Connected to: Mr. X");
            }

            _clientIdByServer = compound4res.resarray.get(0).opexchange_id.eir_resok4.eir_clientid;
            _sequenceID = compound4res.resarray.get(0).opexchange_id.eir_resok4.eir_sequenceid;

            if ((compound4res.resarray.get(0).opexchange_id.eir_resok4.eir_flags.value
                    & nfs4_prot.EXCHGID4_FLAG_USE_PNFS_MDS) != 0) {
                _isMDS = true;
            }

            if ((compound4res.resarray.get(0).opexchange_id.eir_resok4.eir_flags.value
                    & nfs4_prot.EXCHGID4_FLAG_USE_PNFS_DS) != 0) {
                _isDS = true;
            }

            System.out.println("pNFS MDS: " + _isMDS);
            System.out.println("pNFS  DS: " + _isDS);

        } else {
            System.out.println("exchangeId failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private void create_session() throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withCreatesession(_clientIdByServer, _sequenceID)
                .withTag("create_session")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            _sessionid = compound4res.resarray.get(0).opcreate_session.csr_resok4.csr_sessionid;
            // FIXME: no idea why, but other wise server reply MISORDER
            _sequenceID.value.value = 0;

        } else {
            System.out.println("create session failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private void destroy_session() throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withDestroysession(_sessionid)
                .withTag("destroy_session")
                .build();

        @SuppressWarnings("unused")
        COMPOUND4res compound4res = sendCompound(args);
    }

    private void destroy_clientid() throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withDestroyclientid(_clientIdByServer)
                .withTag("destroy_clientid")
                .build();
        @SuppressWarnings("unused")
        COMPOUND4res compound4res = sendCompound(args);
        _nfsClient.close();

    }

    private void getRootFh(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutrootfh()
                .withLookup(path)
                .withGetfh()
                .withTag("get_rootfh")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            _rootFh = compound4res.resarray.get(compound4res.resarray.size() - 1).opgetfh.resok4.object;
            _cwd = _rootFh;
            System.out.println("root fh = " + Bytes.toHexString(_rootFh.value));

        } else {
            System.out.println("getRootFh failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    public void readdir() throws OncRpcException, IOException {

        try {
            for (String entry : list(_cwd)) {
                System.out.println(entry);
            }
        } catch (Exception e) {
            System.out.println("readdir: " + e.getMessage());
        }
    }

    public void readdir(String path) throws OncRpcException, IOException {

        try {
            for (String entry : list(_cwd, path)) {
                System.out.println(entry);
            }
        } catch (Exception e) {
            System.out.println("readdir: " + e.getMessage());
        }
    }

    public String[] list(nfs_fh4 fh) throws OncRpcException, IOException, ChimeraNFSException {

        boolean done;
        List<String> list = new ArrayList<String>();
        long cookie = 0;
        verifier4 verifier = new verifier4(new byte[nfs4_prot.NFS4_VERIFIER_SIZE]);

        do {

            COMPOUND4args args = new CompoundBuilder()
                    .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                    .withPutfh(fh)
                    .withReaddir(cookie, verifier)
                    .withTag("readdir")
                    .build();

            COMPOUND4res compound4res = sendCompound(args);

            if (compound4res.status == nfsstat.NFS_OK) {
                verifier = compound4res.resarray.get(2).opreaddir.resok4.cookieverf;
                done = compound4res.resarray.get(2).opreaddir.resok4.reply.eof;

                entry4 dirEntry = compound4res.resarray.get(2).opreaddir.resok4.reply.entries;
                while (dirEntry != null) {
                    cookie = dirEntry.cookie.value.value;
                    list.add(new String(dirEntry.name.value.value.value));
                    dirEntry = dirEntry.nextentry;
                }

            } else {
                System.out.println("readdir failed. Error = "
                        + nfsstat.toString(compound4res.status));
                done = true;
                throw new ChimeraNFSException(compound4res.status, nfsstat.toString(compound4res.status));
            }

        } while (!done);


        return list.toArray(new String[list.size()]);

    }

    public String[] list(nfs_fh4 fh, String path) throws OncRpcException, IOException, ChimeraNFSException {

        boolean done;
        List<String> list = new ArrayList<String>();
        long cookie = 0;
        verifier4 verifier = new verifier4(new byte[nfs4_prot.NFS4_VERIFIER_SIZE]);

        do {

            COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh( path.charAt(0) == '/' ? _rootFh : fh)
                .withLookup(path)
                .withReaddir(cookie, verifier)
                .withTag("readdir")
                .build();

            COMPOUND4res compound4res = sendCompound(args);

            if (compound4res.status == nfsstat.NFS_OK) {

                verifier = compound4res.resarray.get(compound4res.resarray.size() - 1).opreaddir.resok4.cookieverf;
                done = compound4res.resarray.get(compound4res.resarray.size() - 1).opreaddir.resok4.reply.eof;

                entry4 dirEntry = compound4res.resarray.get(compound4res.resarray.size() - 1).opreaddir.resok4.reply.entries;
                while (dirEntry != null) {
                    cookie = dirEntry.cookie.value.value;
                    list.add(new String(dirEntry.name.value.value.value));
                    dirEntry = dirEntry.nextentry;
                }

            } else {
                System.out.println("readdir failed. Error = "
                        + nfsstat.toString(compound4res.status));
                done = true;
                throw new ChimeraNFSException(compound4res.status, nfsstat.toString(compound4res.status));
            }

        } while (!done);


        return list.toArray(new String[list.size()]);

    }

    private void mkdir(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_cwd)
                .withSavefh()
                .withGetattr(nfs4_prot.FATTR4_CHANGE)
                .withMakedir(path)
                .withRestorefh()
                .withGetattr(nfs4_prot.FATTR4_CHANGE)
                .withTag("mkdir")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status != nfsstat.NFS_OK) {
            System.out.println("mkdir failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }
    }

    private void get_fs_locations(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_cwd)
                .withLookup(path)
                .withGetattr(nfs4_prot.FATTR4_FS_LOCATIONS)
                .withTag("get_fs_locations")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            Map<Integer, Object> attrMap = GetattrStub.decodeType(compound4res.resarray.get(compound4res.resarray.size() - 1).opgetattr.resok4.obj_attributes);

            fattr4_fs_locations locations = (fattr4_fs_locations) attrMap.get(nfs4_prot.FATTR4_FS_LOCATIONS);
            if (locations != null) {
                System.out.println("fs_locations fs_root: " + locations.value.fs_root.value[0].value.toString());
                System.out.println("fs_locations locations rootpath: " + locations.value.locations[0].rootpath.value[0].value.toString());
                System.out.println("fs_locations locations server: " + new String(locations.value.locations[0].server[0].value.value));

            }

        } else {
            System.out.println("get_fs_locations failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    nfs_fh4 cwd(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(path.charAt(0) == '/' ? _rootFh : _cwd)
                .withLookup(path)
                .withGetfh()
                .withTag("lookup (cwd)")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            _cwd = compound4res.resarray.get(compound4res.resarray.size() - 1).opgetfh.resok4.object;
            System.out.println("CWD fh = " + Bytes.toHexString(_cwd.value));

        } else {
            System.out.println("cwd failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

        return new nfs_fh4(_cwd.value);
    }

    public Stat stat(nfs_fh4 fh) throws OncRpcException, IOException {

        Stat stat = new Stat();

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(fh)
                .withGetattr(nfs4_prot.FATTR4_SIZE,nfs4_prot.FATTR4_TYPE)
                .withTag("getattr (stat)")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {


            Map<Integer, Object> attrMap = GetattrStub.decodeType(compound4res.resarray.get(2).opgetattr.resok4.obj_attributes);

            uint64_t size = (uint64_t) attrMap.get(nfs4_prot.FATTR4_SIZE);
            if (size != null) {
                stat.setSize(size.value);
            }

            fattr4_type type = (fattr4_type) attrMap.get(nfs4_prot.FATTR4_TYPE);

            System.out.println("Type is: " + type.value);




        } else {
            System.out.println("getAttr failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }


        return stat;
    }

    private void read(String path) throws OncRpcException, IOException {

        OpenReply or = open(path);

        if (_isMDS) {
            StripeMap stripeMap = layoutget(or.fh(), or.stateid(), layoutiomode4.LAYOUTIOMODE4_READ);

            List<Stripe> stripes = stripeMap.getStripe(0, 4096);
            Stripe stripe = stripes.get(0);
            deviceid4 device = stripe.getDeviceId();
            FileIoDevice ioDevice = _knowDevices.get(device);
            InetSocketAddress deviceAddr =
                    ioDevice.of(stripe.getPatternOffset(), stripe.getUnit(),
                                0, 4096, stripe.getFirstStripeIndex());
            Main dsClient = _servers.getUnchecked(deviceAddr);

            dsClient.nfsRead(stripe.getFh(), or.stateid());

            layoutreturn(or.fh(), 0, -1, new byte[0], or.stateid());

        } else {
            nfsRead(or.fh(), or.stateid());
        }
        close(or.fh(), or.stateid());

    }

    private void readatonce(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh( path.charAt(0) == '/' ? _rootFh : _cwd)
                .withLookup(dirname(path))
                .withOpen(basename(path), _sequenceID.value.value, _clientIdByServer, nfs4_prot.OPEN4_SHARE_ACCESS_READ)
                .withRead(4096, 0, Stateids.currentStateId())
                .withClose(Stateids.currentStateId())
                .withTag("open+read+close")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {
            int opss = compound4res.resarray.size();
            byte[] data = new byte[compound4res.resarray.get(opss-2).opread.resok4.data.remaining()];
            compound4res.resarray.get(opss-2).opread.resok4.data.get(data);
            System.out.println("[" + new String(data) + "]");
        } else {
            System.out.println("open failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }
    }

    private void write(String source, String path) throws OncRpcException, IOException {

        File f = new File(source);
        if (!f.exists()) {
            System.out.println("file not found: " + f);
        }

        OpenReply or = create(path);

        if (_isMDS) {

            StripeMap stripeMap = layoutget(or.fh(), or.stateid(), layoutiomode4.LAYOUTIOMODE4_RW);

            RandomAccessFile raf = null;
            try {

                raf = new RandomAccessFile(source, "r");
                byte[] data = new byte[4096];
                long offset = 0;
                while (true) {

                    int n = raf.read(data);
                    if (n == -1) {
                        break;
                    }

                    /* we got less than 4K wipe the tail */
                    if (n < data.length) {
                        byte[] b = new byte[n];
                        System.arraycopy(data, 0, b, 0, n);
                    }

                    List<Stripe> stripes = stripeMap.getStripe(offset, 4096);
                    Stripe stripe = stripes.get(0);
                    deviceid4 device = stripe.getDeviceId();
                    FileIoDevice ioDevice = _knowDevices.get(device);
                    InetSocketAddress deviceAddr = ioDevice.of(
                            stripe.getPatternOffset(), stripe.getUnit(),
                            offset, data.length, stripe.getFirstStripeIndex());
                    Main dsClient = _servers.getUnchecked(deviceAddr);

                    dsClient.nfsWrite(stripe.getFh(), data, offset, or.stateid());
                    offset += n;
                }

            } catch (IOException ie) {
                System.out.println("Write failed: " + ie.getMessage());
            } finally {
                if (raf != null) {
                    raf.close();
                }
                layoutreturn(or.fh(), 0, -1, new byte[0], or.stateid());
            }

        } else {
            // not a pNFS server
            nfsWrite(or.fh(), "hello world".getBytes(), 0, or.stateid());
        }
        close(or.fh(), or.stateid());
    }

    private OpenReply open(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh( path.charAt(0) == '/' ? _rootFh : _cwd)
                .withLookup(dirname(path))
                .withOpen(basename(path), _sequenceID.value.value, _clientIdByServer, nfs4_prot.OPEN4_SHARE_ACCESS_READ)
                .withGetfh()
                .withTag("open_read")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        int opCount = compound4res.resarray.size();

        if (compound4res.status == nfsstat.NFS_OK) {

            nfs_fh4 fh = compound4res.resarray.get(opCount - 1).opgetfh.resok4.object;
            stateid4 stateid = compound4res.resarray.get(opCount - 2).opopen.resok4.stateid;
            System.out.println("open_read fh = " + Bytes.toHexString(fh.value));

            return new OpenReply(fh, stateid);

        } else {
            System.out.println("open failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

        return null;

    }

    private OpenReply create(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh( path.charAt(0) == '/' ? _rootFh : _cwd)
                .withLookup(dirname(path))
                .withOpenCreate(basename(path), _sequenceID.value.value, _clientIdByServer, nfs4_prot.OPEN4_SHARE_ACCESS_BOTH)
                .withGetfh()
                .withTag("open_create")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {
            int opCount = compound4res.resarray.size();
            nfs_fh4 fh = compound4res.resarray.get(opCount - 1).opgetfh.resok4.object;
            stateid4 stateid = compound4res.resarray.get(opCount - 2).opopen.resok4.stateid;
            System.out.println("open_read fh = " + Bytes.toHexString(fh.value));

            return new OpenReply(fh, stateid);

        } else {
            System.out.println("open failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

        return null;

    }

    private void close(nfs_fh4 fh, stateid4 stateid) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(fh)
                .withClose(stateid)
                .withTag("close")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status != nfsstat.NFS_OK) {

            System.out.println("close failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private StripeMap layoutget(nfs_fh4 fh, stateid4 stateid, int layoutiomode) throws OncRpcException,
            IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(fh)
                .withLayoutget(false,
                layouttype4.LAYOUT4_NFSV4_1_FILES,
                layoutiomode, 0, 0xffffffff, 0xff, 0xffff,
                stateid)
                .withTag("layoutget")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            layout4[] layout = compound4res.resarray.get(2).oplayoutget.logr_resok4.logr_layout;
            System.out.println("Layoutget for fh: " + Bytes.toHexString(fh.value));
            System.out.println("    roc   : " + compound4res.resarray.get(2).oplayoutget.logr_resok4.logr_return_on_close);

            StripeMap stripeMap = new StripeMap();

            for (layout4 l : layout) {
                nfsv4_1_file_layout4 fileDevice = LayoutgetStub.decodeLayoutId(l.lo_content.loc_body);
                System.out.println("       sd # "
                        + Arrays.toString(fileDevice.nfl_deviceid.value) + " size "
                        + fileDevice.nfl_deviceid.value.length);

                _ioFH = fileDevice.nfl_fh_list[0];
                System.out.println("     io fh: " + Bytes.toHexString(_ioFH.value));
                System.out.println("    length: " + l.lo_length.value.value);
                System.out.println("    offset: " + l.lo_offset.value.value);
                System.out.println("    type  : " + l.lo_content.loc_type);
                System.out.println("    unit  : " + fileDevice.nfl_util.value.value);

                deviceid4 deviceID = fileDevice.nfl_deviceid;
                Stripe stripe = new Stripe(deviceID, fileDevice.nfl_fh_list[0],
                        l.lo_length.value.value, l.lo_offset.value.value,
                        fileDevice.nfl_pattern_offset.value.value,
                        fileDevice.nfl_util.value.value,
                        fileDevice.nfl_first_stripe_index.value);
                stripeMap.addStripe(stripe);

                if (!_knowDevices.containsKey(deviceID)) {
                    System.out.println("    new: true");
                    get_deviceinfo(deviceID);
                } else {
                    System.out.println("    new: false");
                }
                FileIoDevice address = _knowDevices.get(deviceID);
                if (address == null) {
                    System.out.println("    address: failed to get");
                } else {
                    System.out.println("    address: " + address);
                }

                return stripeMap;

            }

        } else {
            System.out.println("layoutget failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

        return null;

    }

    private void layoutreturn(nfs_fh4 fh, long offset, long len, byte[] body, stateid4 stateid) throws OncRpcException,
            IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(fh)
                .withLayoutreturn(offset, len, body, stateid)
                .withTag("layoutreturn")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status != nfsstat.NFS_OK) {
            System.out.println("layoutreturn failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private COMPOUND4res sendCompound(COMPOUND4args compound4args)
            throws OncRpcException, IOException {

        COMPOUND4res compound4res;
        /*
         * wail if server is in the grace period.
         *
         * TODO: escape if it takes too long
         */
        do {
            compound4res = _nfsClient.NFSPROC4_COMPOUND_4(compound4args);
            processSequence(compound4res);
            if (compound4res.status == nfsstat.NFSERR_GRACE) {
                System.out.println("Server in GRACE period....retry");
            }
        } while (compound4res.status == nfsstat.NFSERR_GRACE);

        return compound4res;
    }

    private void get_deviceinfo(deviceid4 deviceId) throws OncRpcException,
            IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withGetdeviceinfo(deviceId)
                .withTag("get_deviceinfo")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            nfsv4_1_file_layout_ds_addr4 addr = GetDeviceListStub
                    .decodeFileDevice(compound4res.resarray.get(1).opgetdeviceinfo.gdir_resok4.gdir_device_addr.da_addr_body);

            _knowDevices.put(deviceId, new FileIoDevice(addr) );

        } else {
            System.out.println("getdeviceinfo failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }
    }

    private void get_devicelist() throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_rootFh)
                .withGetdevicelist()
                .withTag("get_devicelist")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            deviceid4[] deviceList = compound4res.resarray.get(2).opgetdevicelist.gdlr_resok4.gdlr_deviceid_list;

            System.out.println("Know devices: ");
            for (deviceid4 device : deviceList) {
                System.out.println("      Device: # " + Arrays.toString(device.value));
            }

        } else {
            System.out.println("get_devicelist failed. Error = "
                    + nfsstat.toString(compound4res.status));
            _isMDS = false;
            _isDS = false;
        }

    }

    private void nfsRead(nfs_fh4 fh, stateid4 stateid)
            throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(fh)
                .withRead(4096, 0, stateid)
                .withTag("pNFS read")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {

            byte[] data = new byte[compound4res.resarray.get(2).opread.resok4.data.remaining()];
            compound4res.resarray.get(2).opread.resok4.data.get(data);
            System.out.println("[" + new String(data) + "]");
        } else {
            System.out.println("read failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private void nfsWrite(nfs_fh4 fh, byte[] data, long offset, stateid4 stateid)
            throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(fh)
                .withWrite(offset, data, stateid)
                .withTag("pNFS write")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status != nfsstat.NFS_OK) {
            throw new IOException(nfsstat.toString(compound4res.status));
        }


        // OK
    }

    private void sequence() throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withTag("sequence")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status != nfsstat.NFS_OK) {

            System.out.println("sequence failed. Error = "
                    + nfsstat.toString(compound4res.status));

        } else {
            // ok
        }
    }

    private void get_supported_attributes() throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_rootFh)
                .withGetattr(nfs4_prot.FATTR4_CHANGE)
                .withTag("get_supported_attributes")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {
            //    uint32_t supported = compound4res.resarray[1].opgetattr.resok4.obj_attributes.attrmask.value[0];
            //    System.out.println(supported);
            // TODO:
        } else {
            System.out.println("get_supported_attributes failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    public void remove(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_cwd)
                .withRemove(path)
                .withTag("remove")
                .build();
        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {
            // ok
        } else {
            System.out.println("remove failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private void lookup(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_cwd)
                .withSavefh()
                .withLookup(path)
                .withGetfh()
                .withGetattr(nfs4_prot.FATTR4_CHANGE,
                nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY)
                .withRestorefh()
                .withGetattr(nfs4_prot.FATTR4_CHANGE,
                nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY)
                .withTag("lookup-sun")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {
            // ok
        } else {
            System.out.println("lookup-sun failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    private void getattr(String path) throws OncRpcException, IOException {

        COMPOUND4args args = new CompoundBuilder()
                .withSequence(false, _sessionid, _sequenceID.value.value, 12, 0)
                .withPutfh(_cwd)
                .withLookup(path)
                .withGetattr(nfs4_prot.FATTR4_CHANGE,
                nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY, nfs4_prot.FATTR4_MODE)
                .withTag("getattr")
                .build();

        COMPOUND4res compound4res = sendCompound(args);

        if (compound4res.status == nfsstat.NFS_OK) {
            Map<Integer, Object> attrMap = GetattrStub.decodeType(compound4res.resarray.get(compound4res.resarray.size() - 1).opgetattr.resok4.obj_attributes);

            mode4 mode = (mode4) attrMap.get(nfs4_prot.FATTR4_MODE);
            if (mode != null) {
                System.out.println("mode: 0" + Integer.toOctalString(mode.value.value));
            }
        } else {
            System.out.println("getattr failed. Error = "
                    + nfsstat.toString(compound4res.status));
        }

    }

    public void processSequence(COMPOUND4res compound4res) {

        if (compound4res.resarray.get(0).resop == nfs_opnum4.OP_SEQUENCE && compound4res.resarray.get(0).opsequence.sr_status == nfsstat.NFS_OK) {
            _lastUpdate = System.currentTimeMillis();
            ++_sequenceID.value.value;
        }
    }

    private static class OpenReply {

        private final nfs_fh4 _fh;
        private final stateid4 _stateid;

        private OpenReply(nfs_fh4 fh, stateid4 stateid) {
            _stateid = stateid;
            _fh = fh;
        }

        nfs_fh4 fh() {
            return _fh;
        }

        stateid4 stateid() {
            return _stateid;
        }
    }

    private final LoadingCache<InetSocketAddress, Main> _servers =
            CacheBuilder.newBuilder().build(new Connector());

    private static class Connector extends CacheLoader<InetSocketAddress, Main> {

        @Override
        public Main load(InetSocketAddress f) {
            try {
                Main client = new Main(f);
                client.dsMount();
                return client;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static String basename(String path) {
        File f = new File(path);
        return f.getName();
    }

    private static String dirname(String path) {
        File f = new File(path);
        String parent = f.getParent();
        return parent == null ? "/" : parent;
    }
}
