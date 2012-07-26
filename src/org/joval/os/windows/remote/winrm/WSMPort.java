// Copyright (C) 2012 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.os.windows.remote.winrm;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.math.BigInteger;
import java.security.PrivilegedActionException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.security.auth.login.LoginException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import org.ietf.jgss.GSSException;
import org.w3c.dom.Element;

//import jcifs.http.NtlmHttpURLConnection;
import net.sourceforge.spnego.SpnegoHttpURLConnection;

import org.dmtf.wsman.MaxEnvelopeSizeType;
import org.xmlsoap.ws.addressing.AttributedURI;
import org.xmlsoap.ws.addressing.EndpointReferenceType;
import org.xmlsoap.ws.transfer.AnyXmlOptionalType;
import org.xmlsoap.ws.transfer.AnyXmlType;
import org.w3c.soap.envelope.Body;
import org.w3c.soap.envelope.Envelope;
import org.w3c.soap.envelope.Fault;
import org.w3c.soap.envelope.Header;

import org.joval.protocol.http.NtlmHttpURLConnection;
import org.joval.util.Base64;

/**
 * An IPort implementation for WS-Management.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class WSMPort implements IPort {
    private static Properties schemaProps = new Properties();
    static {
	ClassLoader cl = Thread.currentThread().getContextClassLoader();
	InputStream rsc = cl.getResourceAsStream("ws-man.properties");
	if (rsc != null) {
	    try {
		schemaProps.load(rsc);
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
    }

    /**
     * Enumeration of supported authentication schemes.
     */
    public enum AuthScheme {
	NONE, BASIC, NTLM, KERBEROS;
    }

    /**
     * JAXB ObjectFactories used by this class.
     */
    public static class Factories {
	public static org.dmtf.wsman.ObjectFactory WSMAN = new org.dmtf.wsman.ObjectFactory();
	public static org.xmlsoap.ws.addressing.ObjectFactory ADDRESS = new org.xmlsoap.ws.addressing.ObjectFactory();
	public static org.w3c.soap.envelope.ObjectFactory SOAP = new org.w3c.soap.envelope.ObjectFactory();
	public static com.microsoft.wsman.config.ObjectFactory WSMC = new com.microsoft.wsman.config.ObjectFactory();
    }

    private AuthScheme scheme;
    private String url;
    private Proxy proxy;
    private String domain, user, pass;
    private Marshaller marshaller;
    private Unmarshaller unmarshaller;

    public WSMPort(String url, String user, String pass) throws JAXBException {
	this(url, null, user, pass);
    }

    /**
     * MS-WSMV client.
     */
    public WSMPort(String url, Proxy proxy, String user, String pass) throws JAXBException {
	JAXBContext ctx = JAXBContext.newInstance(schemaProps.getProperty("ws-man.packages"));
	marshaller = ctx.createMarshaller();
	marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
	marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
	unmarshaller = ctx.createUnmarshaller();
//DAS
scheme = AuthScheme.BASIC;

	this.url = url;
	this.proxy = proxy;
	int ptr = user.indexOf("\\");
	if (ptr == -1) {
	    domain = null;
	    this.user = user;
	} else {
	    domain = user.substring(0, ptr);
	    this.user = user.substring(ptr + 1);
	}
	this.pass = pass;
    }

    // Implement IPort

    public Object dispatch(String action, List<Object> headers, Object input) throws IOException, JAXBException, WSMFault {
	Header header = Factories.SOAP.createHeader();

	AttributedURI to = Factories.ADDRESS.createAttributedURI();
	to.setValue(url);
	to.getOtherAttributes().put(MUST_UNDERSTAND, "true");
	header.getAny().add(Factories.ADDRESS.createTo(to));

	EndpointReferenceType endpointRef = Factories.ADDRESS.createEndpointReferenceType();
	AttributedURI address = Factories.ADDRESS.createAttributedURI();
	address.setValue(REPLY_TO);
	address.getOtherAttributes().put(MUST_UNDERSTAND, "true");
	endpointRef.setAddress(address);
	header.getAny().add(Factories.ADDRESS.createReplyTo(endpointRef));

	AttributedURI soapAction = Factories.ADDRESS.createAttributedURI();
	soapAction.setValue(action);
	soapAction.getOtherAttributes().put(MUST_UNDERSTAND, "true");
	header.getAny().add(Factories.ADDRESS.createAction(soapAction));

	AttributedURI messageId = Factories.ADDRESS.createAttributedURI();
	messageId.setValue("uuid:" + UUID.randomUUID().toString());
	header.getAny().add(Factories.ADDRESS.createMessageID(messageId));

	for (Object obj : headers) {
	    header.getAny().add(obj);
	}

	MaxEnvelopeSizeType size = Factories.WSMAN.createMaxEnvelopeSizeType();
	size.setValue(BigInteger.valueOf(51200));
	size.getOtherAttributes().put(MUST_UNDERSTAND, "true");
	header.getAny().add(Factories.WSMAN.createMaxEnvelopeSize(size));

	//
	// Convert any non-directly-serializable WS-Transfer input types
	//
	if (input instanceof AnyXmlType) {
	    input = ((AnyXmlType)input).getAny();
	} else if (input instanceof AnyXmlOptionalType) {
	    input = ((AnyXmlOptionalType)input).getAny();
	}

	Body body = Factories.SOAP.createBody();
	if (input != null) {
	    body.getAny().add(input);
	}

	Envelope request = Factories.SOAP.createEnvelope();
	request.setHeader(header);
	request.setBody(body);

	URL u = new URL(url);
	boolean success = false;
	Object result = null;
	HttpURLConnection conn = null;
	SpnegoHttpURLConnection spnego = null;
	do {
	    try {
		if (conn != null) {
		    conn.disconnect();
		}

System.out.println("Connecting to " + url + " using authScheme " + scheme);
		switch(scheme) {
		  case NONE:
		    if (proxy == null) {
			conn = (HttpURLConnection)u.openConnection();
		    } else {
			conn = (HttpURLConnection)u.openConnection(proxy);
		    }
		    break;

		  case KERBEROS:
		    System.setProperty("java.security.krb5.conf", "krb5.conf");
		    System.setProperty("sun.security.krb5.debug", "true");
		    System.setProperty("java.security.auth.login.config", "login.conf");
		    System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
		    spnego = new SpnegoHttpURLConnection("com.sun.security.jgss.krb5.initiate", user, pass);
		    if (proxy == null) {
			conn = spnego.connect(u);
		    } else {
			conn = spnego.connect(u, proxy);
		    }
		    break;

		  case NTLM:
		    URL authURL = new URL(u.getProtocol() + "://" +
					  domain + "\\" + user + ":" + pass + "@" +
					  u.getHost() + ":" + u.getPort() + u.getPath());
		    if (proxy == null) {
			conn = new NtlmHttpURLConnection((HttpURLConnection)authURL.openConnection());
		    } else {
			conn = new NtlmHttpURLConnection((HttpURLConnection)authURL.openConnection(proxy));
		    }
		    break;

		  case BASIC:
		    if (proxy == null) {
			conn = (HttpURLConnection)u.openConnection();
		    } else {
			conn = (HttpURLConnection)u.openConnection(proxy);
		    }
		    conn.setRequestProperty("Authorization", "Basic " + Base64.encodeBytes((user + ":" + pass).getBytes()));
		    break;
		}

		conn.setDoInput(true);
		conn.setDoOutput(true);
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/soap+xml;charset=UTF-8");
		conn.setRequestProperty("SOAPAction", action);
		conn.setRequestProperty("Accept", "*/*");

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		marshaller.marshal(Factories.SOAP.createEnvelope(request), buffer);
		byte[] bytes = buffer.toByteArray();
		conn.setFixedLengthStreamingMode(bytes.length);

		conn.connect();
		OutputStream out = conn.getOutputStream();
		out.write(bytes);
		out.flush();

		int code = conn.getResponseCode();
		switch(code) {
		  case HttpURLConnection.HTTP_OK:
		    result = getSOAPBodyContents(conn.getInputStream());
		    success = true;
		    break;

		  case HttpURLConnection.HTTP_UNAUTHORIZED:
		    debug(conn);
		    break;

		  case HttpURLConnection.HTTP_BAD_REQUEST:
		  case HttpURLConnection.HTTP_INTERNAL_ERROR:
		    result = getSOAPBodyContents(conn.getErrorStream());
		    break;

		  default:
		    System.out.println("Bad response: " + code);
		    debug(conn);
		    break;
		}
	    } catch (GSSException e) {
		e.printStackTrace();
	    } catch (LoginException e) {
		e.printStackTrace();
	    } catch (PrivilegedActionException e) {
		e.printStackTrace();
	    } finally {
		if (conn != null) {
		    conn.disconnect();
		}
		if (spnego != null) {
		    spnego.disconnect();
		}
	    }
	} while (!success && nextAuthScheme(conn));

	if (result instanceof JAXBElement) {
//DAS
marshaller.marshal(result, System.out);
	    result = ((JAXBElement)result).getValue();
	}
	if (result instanceof Fault) {
	    throw new WSMFault((Fault)result);
	} else {
System.out.println("Result is of type " + result.getClass().getName());
	    return result;
	}
    }

    // Private

    /**
     * Read a SOAP envelope and return the unmarshalled object contents of the body.
     */
    private Object getSOAPBodyContents(InputStream in) throws JAXBException, IOException {
	Object result = unmarshaller.unmarshal(in);
	if (result instanceof JAXBElement) {
	    JAXBElement elt = (JAXBElement)result;
	    if (elt.getValue() instanceof Envelope) {
		List<Object> list = ((Envelope)elt.getValue()).getBody().getAny();
		switch(list.size()) {
		  case 0:
		    return null;
		  case 1:
		    return list.get(0);
		  default:
		    return list;
		}
	    } else {
		System.out.println("Unsupported element contents: " + elt.getValue().getClass().getName());
	    }
	} else {
	    System.out.println("Unsupported class: " + result.getClass().getName());
	}
	return null;
    }

    /**
     * Spit out information about a non-HTTP_OK reply.
     */
    private void debug(HttpURLConnection conn) throws IOException {
	int len = conn.getHeaderFields().size();
	if (len > 0) {
	    System.out.println("HTTP Headers:");
	    System.out.println("  " + conn.getHeaderField(0));
	    for (int i=1; i < len; i++) {
		System.out.println("  " + conn.getHeaderFieldKey(i) + ": " + conn.getHeaderField(i));
	    }
	}
	if (conn.getContentLength() > 0) {
	    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
	    String line = null;
	    while((line = reader.readLine()) != null) {
		System.out.println(line);
	    }
	}
    }

    /**
     * Set the authentication scheme for the retry, or return false if there are no more options.
     */
    private boolean nextAuthScheme(HttpURLConnection conn) {
	if (conn == null) {
	    return false;
	}

	List<String> authFields = conn.getHeaderFields().get("WWW-Authenticate");
	if (authFields == null || authFields.size() == 0) {
	    switch(scheme) {
	      case NONE:
		return false;
	      default:
		scheme = AuthScheme.NONE;
		return true;
	    }
	}

	boolean basic = false;
	boolean ntlm = false;
	boolean kerberos = false;
	boolean negotiate = false;

	for (String val : authFields) {
	    if (val.toLowerCase().startsWith("basic")) {
		basic = true;
	    } else if (val.equalsIgnoreCase("Negotiate")) {
		negotiate = true;
	    } else if (val.equalsIgnoreCase("Kerberos")) {
		kerberos = true;
	    } else if (val.equalsIgnoreCase("NTLM")) {
		ntlm = true;
	    }
	}

	if (basic) {
	    switch(scheme) {
	      case BASIC:
		return false;
	      default:
		scheme = AuthScheme.BASIC;
		return true;
	    }
	}
	if (negotiate) {
	    switch(scheme) {
	      case NONE:
	      case BASIC:
		scheme = AuthScheme.NTLM;
		return true;
	      case NTLM:
		scheme = AuthScheme.KERBEROS;
		return true;
	      case KERBEROS:
		return false;
	    }
	}
	if (ntlm) {
	    switch(scheme) {
	      case NTLM:
		return false;
	      default:
		scheme = AuthScheme.NTLM;
		return true;
	    }
	}
	if (kerberos) {
	    switch(scheme) {
	      case KERBEROS:
		return false;
	      default:
		scheme = AuthScheme.KERBEROS;
		return true;
	    }
	}
	return false;
    }
}
