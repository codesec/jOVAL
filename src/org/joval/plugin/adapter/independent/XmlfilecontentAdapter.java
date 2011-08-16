// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.plugin.adapter.independent;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.logging.Level;
import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathExpressionException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import oval.schemas.common.MessageLevelEnumeration;
import oval.schemas.common.MessageType;
import oval.schemas.definitions.independent.XmlfilecontentObject;
import oval.schemas.systemcharacteristics.core.EntityItemAnySimpleType;
import oval.schemas.systemcharacteristics.core.EntityItemStringType;
import oval.schemas.systemcharacteristics.core.ItemType;
import oval.schemas.systemcharacteristics.core.StatusEnumeration;
import oval.schemas.systemcharacteristics.independent.XmlfilecontentItem;
import oval.schemas.results.core.ResultEnumeration;

import org.joval.intf.io.IFile;
import org.joval.intf.io.IFilesystem;
import org.joval.intf.plugin.IAdapter;
import org.joval.intf.plugin.IRequestContext;
import org.joval.oval.OvalException;
import org.joval.util.BaseFileAdapter;
import org.joval.util.JOVALSystem;
import org.joval.util.StringTools;

/**
 * Evaluates Xmlfilecontent OVAL tests.
 *
 * DAS: Specify a maximum file size supported
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class XmlfilecontentAdapter extends BaseFileAdapter {
    private DocumentBuilder builder;

    public XmlfilecontentAdapter(IFilesystem fs) {
	super(fs);
	try {
	    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	    factory.setNamespaceAware(true);
	    builder = factory.newDocumentBuilder();
	} catch (ParserConfigurationException e) {
	    JOVALSystem.getLogger().log(Level.WARNING, e.getMessage(), e);
	}
    }

    // Implement IAdapter

    public Class getObjectClass() {
	return XmlfilecontentObject.class;
    }

    // Protected

    protected Object convertFilename(EntityItemStringType filename) {
	return filename;
    }

    protected ItemType createFileItem() {
	return JOVALSystem.factories.sc.independent.createXmlfilecontentItem();
    }

    /**
     * Parse the file as specified by the Object, and decorate the Item.
     */
    protected List<JAXBElement<? extends ItemType>> getItems(ItemType base, IFile f, IRequestContext rc)
		throws IOException, OvalException {

	List<JAXBElement<? extends ItemType>> items = new Vector<JAXBElement<? extends ItemType>>();

	XmlfilecontentItem baseItem = null;
	if (base instanceof XmlfilecontentItem) {
	    baseItem = (XmlfilecontentItem)base;
	}
	XmlfilecontentObject xObj = null;
	if (rc.getObject() instanceof XmlfilecontentObject) {
	    xObj = (XmlfilecontentObject)rc.getObject();
	}

	if (baseItem != null && xObj != null && f.isFile()) {
	    XmlfilecontentItem item = (XmlfilecontentItem)createFileItem();
	    item.setPath(baseItem.getPath());
	    item.setFilename(baseItem.getFilename());
	    item.setFilepath(baseItem.getFilepath());
	    EntityItemStringType xpathType = JOVALSystem.factories.sc.core.createEntityItemStringType();
	    String expression = (String)xObj.getXpath().getValue();
	    xpathType.setValue(expression);
	    item.setXpath(xpathType);

	    InputStream in = null;
	    try {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = xpath.compile(expression);
		in = f.getInputStream();
		Document doc = builder.parse(in);

		for (String value : typesafeEval(expr, doc)) {
		    EntityItemAnySimpleType valueOf = JOVALSystem.factories.sc.core.createEntityItemAnySimpleType();
		    valueOf.setValue(value);
		    item.getValueOf().add(valueOf);
		}

		items.add(JOVALSystem.factories.sc.independent.createXmlfilecontentItem(item));
	    } catch (XPathExpressionException e) {
		MessageType msg = JOVALSystem.factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(JOVALSystem.getMessage("ERROR_XML_XPATH", expression, e.getMessage()));
		rc.addMessage(msg);
		JOVALSystem.getLogger().log(Level.WARNING, e.getMessage(), e);
	    } catch (SAXException e) {
		MessageType msg = JOVALSystem.factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(JOVALSystem.getMessage("ERROR_XML_PARSE", f.getLocalName(), e.getMessage()));
		rc.addMessage(msg);
		JOVALSystem.getLogger().log(Level.WARNING, e.getMessage(), e);
	    } finally {
		if (in != null) {
		    try {
			in.close();
		    } catch (IOException e) {
			JOVALSystem.getLogger().log(Level.WARNING,
						    JOVALSystem.getMessage("ERROR_FILE_STREAM_CLOSE", f.toString()), e);
		    }
		}
	    }
	}
	return items;
    }

    private List<String> typesafeEval(XPathExpression expr, Document doc) throws OvalException {
	Stack<QName> types = new Stack<QName>();
	types.push(XPathConstants.BOOLEAN);
	types.push(XPathConstants.NODE);
	types.push(XPathConstants.NODESET);
	types.push(XPathConstants.NUMBER);
	types.push(XPathConstants.STRING);

	return typesafeEval(expr, doc, types);
    }

    private List<String> typesafeEval(XPathExpression exp, Document doc, Stack<QName> types) throws OvalException {
	List<String> list = new Vector<String>();
	if (types.empty()) {
	    return list;
	}
	try {
	    QName qn = types.pop();
	    Object o = exp.evaluate(doc, qn);
	    if (o instanceof String) {
		list.add((String)o);
	    } else if (o instanceof NodeList) {
		NodeList nodes = (NodeList)o;
		int len = nodes.getLength();
		for (int i=0; i < len; i++) {
		    list.add(nodes.item(i).getNodeValue());
		}
	    } else if (o instanceof Double) {
		list.add(((Double)o).toString());
	    } else if (o instanceof Boolean) {
		list.add(((Boolean)o).toString());
	    }
	    return list;
	} catch (XPathExpressionException e) {
	    return typesafeEval(exp, doc, types);
	}
    }
}
