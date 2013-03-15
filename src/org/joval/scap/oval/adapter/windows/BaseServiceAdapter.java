// Copyright (C) 2013 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.scap.oval.adapter.windows;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.bind.JAXBElement;

import jsaf.intf.windows.powershell.IRunspace;
import jsaf.intf.windows.system.IWindowsSession;
import jsaf.util.SafeCLI;

import scap.oval.common.MessageLevelEnumeration;
import scap.oval.common.MessageType;
import scap.oval.common.OperationEnumeration;
import scap.oval.common.SimpleDatatypeEnumeration;
import scap.oval.definitions.core.EntityObjectStringType;
import scap.oval.definitions.core.ObjectType;
import scap.oval.systemcharacteristics.core.EntityItemStringType;
import scap.oval.systemcharacteristics.core.FlagEnumeration;
import scap.oval.systemcharacteristics.core.ItemType;
import scap.oval.systemcharacteristics.core.StatusEnumeration;

import org.joval.intf.plugin.IAdapter;
import org.joval.scap.oval.CollectException;
import org.joval.scap.oval.Factories;
import org.joval.util.JOVALMsg;
import org.joval.xml.XSITools;

/**
 * Base class for Service-based IAdapters. Subclasses need only implement getItemClass and getItems methods.
 * The base class handles searches and caching of search results.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public abstract class BaseServiceAdapter<T extends ItemType> implements IAdapter {
    private IRunspace rs;
    private Collection<String> serviceNames;

    protected IWindowsSession session;

    /**
     * Subclasses can call this method to initialize the session variable.
     */
    protected void init(IWindowsSession session) {
	this.session = session;
    }

    // Implement IAdapter

    public final Collection<T> getItems(ObjectType obj, IRequestContext rc) throws CollectException {
	if (serviceNames == null) {
	    serviceNames = new HashSet<String>();
	    try {
		for (String serviceName : getRunspace().invoke("Get-Service | %{$_.Name}").split("\n")) {
		    serviceNames.add(serviceName.trim());
		}
	    } catch (Exception e) {
		throw new CollectException(e, FlagEnumeration.ERROR);
	    }
	}

	Collection<T> items = new ArrayList<T>();
	ReflectedServiceObject sObj = new ReflectedServiceObject(obj);

	//
	// Find all the matching services 
	//
	OperationEnumeration op = sObj.getServiceName().getOperation();
	Collection<String> names = new ArrayList<String>();
	String name = (String)sObj.getServiceName().getValue();
	switch(op) {
	  case CASE_INSENSITIVE_EQUALS:
	  case EQUALS:
	    for (String serviceName : serviceNames) {
		if (name.equalsIgnoreCase(serviceName)) {
		    names.add(name);
		    break;
		}
	    }
	    break;
	  case CASE_INSENSITIVE_NOT_EQUAL:
	  case NOT_EQUAL:
	    for (String serviceName : serviceNames) {
		if (!name.equalsIgnoreCase(serviceName)) {
		    names.add(serviceName);
		    break;
		}
	    }
	    break;
	  case PATTERN_MATCH: {
	    Pattern p = Pattern.compile(name);
	    for (String serviceName : serviceNames) {
		if (p.matcher(serviceName).find()) {
		    names.add(serviceName);
		}
	    }
	    break;
	  }
	  default:
	    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
	    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
	}

	//
	// For each matching service name, get items.
	//
	for (String serviceName : names) {
	    try {
		//
		// Create the base ItemType for the path
		//
		ReflectedServiceItem sItem = new ReflectedServiceItem();
		EntityItemStringType serviceNameType = Factories.sc.core.createEntityItemStringType();
		serviceNameType.setValue(serviceName);
		serviceNameType.setDatatype(SimpleDatatypeEnumeration.STRING.value());
		sItem.setServiceName(serviceNameType);

		//
		// Add items retrieved by the subclass
		//
		items.addAll(getItems(obj, sItem.it, rc));
	    } catch (NoSuchElementException e) {
		// No match.
	    } catch (CollectException e) {
		throw e;
	    } catch (Exception e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(e.getMessage());
		rc.addMessage(msg);
		session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	}
	return items;
    }

    // Protected

    /**
     * Return the Class of the ItemTypes generated by the subclass.
     */
    protected abstract Class getItemClass();

    /**
     * Return a list of items to associate with the given ObjectType, based on the service specified by the base parameter.
     *
     * @arg base the base ItemType containing name information already populated
     *
     * @throws NoSuchElementException if no matching item is found
     * @throws CollectException collection cannot take place and should be halted
     */
    protected abstract Collection<T> getItems(ObjectType obj, ItemType base, IRequestContext rc) throws Exception;

    /**
     * Subclasses should override by supplying streams to any modules that must be loaded into requested runspaces using
     * the getRunspace method, below.
     */
    protected List<InputStream> getPowershellModules() {
	@SuppressWarnings("unchecked")
	List<InputStream> empty = (List<InputStream>)Collections.EMPTY_LIST;
	return empty;
    }

    /**
     * Get a runspace.
     */
    protected IRunspace getRunspace() throws Exception {
	if (rs == null) {
	    rs = createRunspace();
	}
	return rs;
    }

    // Private

    /**
     * Create a runspace with the native view. Modules supplied by getPowershellModules() will be auto-loaded before
     * the runspace is returned.
     */
    private IRunspace createRunspace() throws Exception {
	IRunspace result = null;
	for (IRunspace runspace : session.getRunspacePool().enumerate()) {
	    if (session.getNativeView() == runspace.getView()) {
		result = runspace;
		break;
	    }
	}
	if (result == null) {
	    result = session.getRunspacePool().spawn();
	}
	for (InputStream in : getPowershellModules()) {
	    result.loadModule(in);
	}
	return result;
    }

    /**
     * A reflection proxy for:
     *     scap.oval.definitions.windows.ServiceObject
     *     scap.oval.definitions.windows.ServiceeffectiverightsObject
     */
    class ReflectedServiceObject {
	ObjectType obj;
	String id = null;
	EntityObjectStringType serviceName = null;

	ReflectedServiceObject(ObjectType obj) throws CollectException {
	    this.obj = obj;

	    try {
		Method getId = obj.getClass().getMethod("getId");
		Object o = getId.invoke(obj);
		if (o != null) {
		    id = (String)o;
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }

	    try {
		Method getServiceName = obj.getClass().getMethod("getServiceName");
		Object o = getServiceName.invoke(obj);
		if (o != null) {
		    serviceName = (EntityObjectStringType)o;
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }
	}

	public ObjectType getObject() {
	    return obj;
	}

	public String getId() {
	    return id;
	}

	public boolean isSetServiceName() {
	    return serviceName != null;
	}

	public EntityObjectStringType getServiceName() {
	    return serviceName;
	}
    }

    /**
     * A reflection proxy for:
     *     scap.oval.systemcharacteristics.windows.ServiceItem
     *     scap.oval.systemcharacteristics.windows.ServiceeffectiverightsItem
     */
    class ReflectedServiceItem {
	ItemType it;
	Method setServiceName, setStatus;
	Object factory;

	ReflectedServiceItem() throws ClassNotFoundException, InstantiationException, NoSuchMethodException,
		IllegalAccessException, InvocationTargetException {

	    Class clazz = getItemClass();
	    String className = clazz.getName();
	    String packageName = clazz.getPackage().getName();
	    String unqualClassName = className.substring(packageName.length() + 1);
	    Class<?> factoryClass = Class.forName(packageName + ".ObjectFactory");
	    factory = factoryClass.newInstance();
	    Method createType = factoryClass.getMethod("create" + unqualClassName);
	    it = (ItemType)createType.invoke(factory);

	    Method[] methods = it.getClass().getMethods();
	    for (int i=0; i < methods.length; i++) {
		String name = methods[i].getName();
		if ("setServiceName".equals(name)) {
		    setServiceName = methods[i];
		} else if ("setStatus".equals(name)) {
		    setStatus = methods[i];
		}
	    }
	}

	void setServiceName(EntityItemStringType serviceName)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (setServiceName != null) {
		setServiceName.invoke(it, serviceName);
	    }
	}

	void setStatus(StatusEnumeration status)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (setStatus != null) {
		setStatus.invoke(it, status);
	    }
	}
    }
}
