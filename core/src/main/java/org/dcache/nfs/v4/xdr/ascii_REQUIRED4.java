/*
 * Automatically generated by jrpcgen 1.0.7+ on 4/3/20, 4:01 PM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 *
 * This version of jrpcgen adopted by dCache project
 * See http://www.dCache.ORG for details
 */
package org.dcache.nfs.v4.xdr;
import org.dcache.oncrpc4j.rpc.*;
import org.dcache.oncrpc4j.rpc.net.*;
import org.dcache.oncrpc4j.xdr.*;
import java.io.IOException;

public class ascii_REQUIRED4 implements XdrAble {

    public utf8string value;

    public ascii_REQUIRED4() {
    }

    public ascii_REQUIRED4(utf8string value) {
        this.value = value;
    }

    public ascii_REQUIRED4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        value.xdrEncode(xdr);
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        value = new utf8string(xdr);
    }

}
// End of ascii_REQUIRED4.java