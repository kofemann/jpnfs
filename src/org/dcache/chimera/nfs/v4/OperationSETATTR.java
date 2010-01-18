package org.dcache.chimera.nfs.v4;

import org.dcache.chimera.nfs.v4.xdr.int32_t;
import org.dcache.chimera.nfs.v4.xdr.utf8str_cs;
import org.dcache.chimera.nfs.v4.xdr.nfs4_prot;
import org.dcache.chimera.nfs.v4.xdr.bitmap4;
import org.dcache.chimera.nfs.v4.xdr.nfs_argop4;
import org.dcache.chimera.nfs.v4.xdr.fattr4_acl;
import org.dcache.chimera.nfs.v4.xdr.nfsstat4;
import org.dcache.chimera.nfs.v4.xdr.settime4;
import org.dcache.chimera.nfs.v4.xdr.uint32_t;
import org.dcache.chimera.nfs.v4.xdr.fattr4;
import org.dcache.chimera.nfs.v4.xdr.time_how4;
import org.dcache.chimera.nfs.v4.xdr.nfstime4;
import org.dcache.chimera.nfs.v4.xdr.uint64_t;
import org.dcache.chimera.nfs.v4.xdr.mode4;
import org.dcache.chimera.nfs.v4.xdr.nfs_opnum4;
import org.dcache.chimera.nfs.v4.xdr.SETATTR4res;
import org.dcache.chimera.nfs.ChimeraNFSException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.dcache.xdr.XdrDecodingStream;
import org.dcache.xdr.RpcCall;
import org.dcache.chimera.FileSystemProvider;
import org.dcache.chimera.FsInode;
import org.dcache.chimera.nfs.ExportFile;
import org.dcache.chimera.nfs.v4.acl.AclStore;
import org.dcache.chimera.posix.AclHandler;
import org.dcache.chimera.posix.Stat;
import org.dcache.chimera.posix.UnixAcl;
import org.dcache.xdr.XdrBuffer;

public class OperationSETATTR extends AbstractNFSv4Operation {


	private static final Logger _log = Logger.getLogger(OperationSETATTR.class.getName());

	OperationSETATTR(FileSystemProvider fs, RpcCall call$, CompoundArgs fh, nfs_argop4 args, ExportFile exports) {
		super(fs, exports, call$, fh, args, nfs_opnum4.OP_SETATTR);
	}

	@Override
	public NFSv4OperationResult process() {


    	SETATTR4res res = new SETATTR4res();

    	try {

    		Stat inodeStat = _fh.currentInode().statCache();

            UnixAcl acl = new UnixAcl(inodeStat.getUid(), inodeStat.getGid(),inodeStat.getMode() & 0777 );
            if ( ! _permissionHandler.isAllowed(acl, _user, AclHandler.ACL_ADMINISTER) ) {
                throw new ChimeraNFSException( nfsstat4.NFS4ERR_ACCESS, "Permission denied."  );
            }

           res.status = nfsstat4.NFS4_OK;
           res.attrsset = setAttributes(_args.opsetattr.obj_attributes, _fh.currentInode());

        }catch(ChimeraNFSException hfe) {
    		res.status = hfe.getStatus();
    		res.attrsset = new bitmap4();
    		res.attrsset.value = new uint32_t[2];
    		res.attrsset.value[0] = new uint32_t(0);
    		res.attrsset.value[1] = new uint32_t(0);
    	}catch(Exception e) {
            _log.log(Level.SEVERE, "SETATTR4:", e);
    		res.status = nfsstat4.NFS4ERR_SERVERFAULT;
    	}


        _result.opsetattr = res;

        return new NFSv4OperationResult(_result, res.status);

	}

    static bitmap4 setAttributes(fattr4 attributes, FsInode inode) throws Exception {

        _log.log(Level.FINEST, "set Attribute length: {0}", attributes.attrmask.value.length);

        int[] mask = new int[attributes.attrmask.value.length];
        for( int i = 0; i < mask.length; i++) {
            mask[i] = attributes.attrmask.value[i].value;
            _log.log(Level.FINEST, "setAttributes[{0}]: {1}",
                    new Object[] {i, Integer.toBinaryString(mask[i])}
            );
        }

        XdrDecodingStream xdr = new XdrBuffer( ByteBuffer.wrap(attributes.attr_vals.value));
        xdr.beginDecoding();

        int[] retMask = new int[mask.length];

        if( mask.length != 0 ) {
            int maxAttr = 32*mask.length;
            for( int i = 0; i < maxAttr; i++) {
                int newmask = (mask[i/32] >> (i-(32*(i/32))) );
                if( (newmask & 1L) != 0 ) {
                    if( xdr2fattr(i, inode, xdr) ) {
                        _log.log(Level.FINEST, "   setAttributes : {0} ({1}) OK",
                            new Object[] {i, OperationGETATTR.attrMask2String(i)}
                        );
                        int attrmask = 1 << (i-(32*(i/32)));
                        retMask[i/32] |= attrmask;
                    }else{
                        _log.log(Level.FINEST, "   setAttributes : {0} ({1}) NOT SUPPORTED",
                            new Object[] {i, OperationGETATTR.attrMask2String(i)}
                        );
                        throw new ChimeraNFSException( nfsstat4.NFS4ERR_ATTRNOTSUPP, "attribute "+ OperationGETATTR.attrMask2String(i) +" not supported");
                    }
                }
            }
        }

        xdr.endDecoding();


        bitmap4 bitmap = new bitmap4();
        bitmap.value = new uint32_t[retMask.length];
        for(int k = 0; k < retMask.length; k++) {
            bitmap.value[k] = new uint32_t(retMask[k]);
        }

        return bitmap;
    }

    static boolean xdr2fattr( int fattr , FsInode inode, XdrDecodingStream xdr) throws Exception {

        boolean isApplied = false;

        _log.log(Level.FINEST, "    FileAttribute: {0}", fattr);

        switch(fattr) {

            case nfs4_prot.FATTR4_SIZE :

            	if( inode.isDirectory() ) {
                    throw new ChimeraNFSException(nfsstat4.NFS4ERR_ISDIR, "path is a directory");
            	}

            	if( inode.isLink() ) {
                    throw new ChimeraNFSException(nfsstat4.NFS4ERR_INVAL, "path is a symbolic link");
            	}

                uint64_t size = new uint64_t();
                size.xdrDecode(xdr);
                inode.setSize(size.value);
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_ACL :
                fattr4_acl acl = new fattr4_acl();
                acl.xdrDecode(xdr);
                for(int i = 0; i < acl.value.length; i++ ) {
                	_log.info("newacl: " + HimeraNFS4Utils.aceToString(acl.value[i]));
                }

                /*
                 * TODO: here is the place to call ACL module
                 */

                AclStore.getInstance().setAcl(inode, acl.value);
                inode.setMTime(System.currentTimeMillis());

                isApplied = true;
                break;
            case nfs4_prot.FATTR4_ARCHIVE :
                int32_t isArchive = new int32_t();
                isArchive.xdrDecode(xdr);
                isApplied = false;
                break;
            case nfs4_prot.FATTR4_HIDDEN :
                int32_t isHidden = new int32_t();
                isHidden.xdrDecode(xdr);
                isApplied = false;
                break;
            case nfs4_prot.FATTR4_MIMETYPE :
                utf8str_cs mimeType = new utf8str_cs();
                mimeType.xdrDecode(xdr);
                isApplied = false;
                break;
            case nfs4_prot.FATTR4_MODE :
                mode4 mode = new mode4();
                mode.xdrDecode(xdr);
                inode.setMode( mode.value.value );
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_OWNER :
                // TODO: use princilat
                utf8str_cs owner = new utf8str_cs ();
                owner.xdrDecode(xdr);
                String new_owner = new String(owner.value.value);
                _log.log(Level.FINEST, "new owner: {0}", new_owner );
                if( new_owner.matches("[0-9]+") ){
                    // already numeric
                    inode.setUID(Integer.parseInt(new_owner));
                }
                //else{
                //    org.dcache.chimera.posix.UnixUser user = (org.dcache.chimera.posix.UnixUser)_nisDirectory.lookup(new_owner);
                //    if(user != null) {
                //       inode.setUID(user.getUID());
                //    }
                // }
                // default is false
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_OWNER_GROUP :
                // TODO: use princilat
                utf8str_cs owner_group = new utf8str_cs ();
                owner_group.xdrDecode(xdr);
                String new_group = new String(owner_group.value.value);
                _log.log(Level.FINEST, "new owner_group: {0}", new_group);
                if( new_group.matches("[0-9]+") ){
                    // already numeric
                    inode.setGID(Integer.parseInt(new_group));
                }
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_SYSTEM :
                int32_t isSystem = new int32_t();
                isSystem.xdrDecode(xdr);
                isApplied = false;
                break;
            case nfs4_prot.FATTR4_TIME_ACCESS_SET :
                settime4 atime = new settime4();
                atime.xdrDecode(xdr);
                // ignore for performance
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_TIME_BACKUP :
                nfstime4 btime = new nfstime4();
                btime.xdrDecode(xdr);
                isApplied = false;
                break;
            case nfs4_prot.FATTR4_TIME_CREATE :
                nfstime4 ctime = new nfstime4();
                ctime.xdrDecode(xdr);
                inode.setCTime( TimeUnit.MILLISECONDS.convert(ctime.seconds.value, TimeUnit.SECONDS) +
                		TimeUnit.MILLISECONDS.convert(ctime.nseconds.value, TimeUnit.NANOSECONDS));
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_TIME_MODIFY_SET :
                settime4 setMtime = new settime4();
                setMtime.xdrDecode(xdr);

                long realMtime = 0;
                if( setMtime.set_it == time_how4.SET_TO_SERVER_TIME4 ) {
                    realMtime = System.currentTimeMillis();
                }else{
                    realMtime = TimeUnit.MILLISECONDS.convert(setMtime.time.seconds.value, TimeUnit.SECONDS) +
                    	TimeUnit.MILLISECONDS.convert(setMtime.time.nseconds.value, TimeUnit.NANOSECONDS);
                }

                inode.setMTime( realMtime );
                isApplied = true;
                break;
            case nfs4_prot.FATTR4_SUPPORTED_ATTRS:
                throw new ChimeraNFSException(nfsstat4.NFS4ERR_INVAL, "setattr of read-only attributes");
        }

        if(!isApplied ) {
            _log.log(Level.INFO, "Attribute not applied: {0}", OperationGETATTR.attrMask2String(fattr) );
        }
        return isApplied;
    }

}