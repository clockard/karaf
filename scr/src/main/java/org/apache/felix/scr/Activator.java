/*
 *   Copyright 2006 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.felix.scr;

import java.lang.reflect.Method;
import java.util.*;

import org.osgi.framework.*;
import org.osgi.service.log.LogService;

/**
 * This activator is used to cover requirement described in section 112.8.1 @@ -27,14
 * 37,202 @@ in active bundles.
 * 
 */
public class Activator implements BundleActivator, SynchronousBundleListener, ServiceListener
{
    // name of the LogService class
    private static final String LOGSERVICE_CLASS = LogService.class.getName();
    
    // Flag that sets tracing messages
    private static boolean m_trace = true;
    
    // Flag that sets error messages
    private static boolean m_error = true;

    // A string containing the version number
    private static String m_version = "1.0.0 (12012006)";

    // this bundle's context
    private BundleContext m_context;
    
    // the log service to log messages to
    private static /* TODO: not very good, is it ? */ LogService m_logService;
    
    // map of BundleComponentActivator instances per Bundle indexed by Bundle symbolic
    // name
    private Map m_componentBundles;

    // Static initializations based on system properties
    static {
        // Get system properties to see if traces or errors need to be displayed
        String result = System.getProperty("ds.showtrace");
        if(result != null && result.equals("true"))
        {
            m_trace = true;
        }
        result = System.getProperty("ds.showerrors");
        if(result != null && result.equals("false"))
        {
            m_error = false;
        }
        result = System.getProperty("ds.showversion");
        if(result != null && result.equals("true"))
        {
            System.out.println("[ Version = "+m_version+" ]\n");
        }        
    }

    /**
     * Registers this instance as a (synchronous) bundle listener and loads the
     * components of already registered bundles.
     * 
     * @param context The <code>BundleContext</code> of the SCR implementation
     *      bundle.
     */
    public void start(BundleContext context) throws Exception
    {
        m_context = context;
        m_componentBundles = new HashMap();

        // require the log service
        ServiceReference logRef = context.getServiceReference(LOGSERVICE_CLASS);
        if (logRef != null) {
            m_logService = (LogService) context.getService(logRef);
        }
        context.addServiceListener(this,
            "(" + Constants.OBJECTCLASS + "=" + LOGSERVICE_CLASS + ")");
        
        // register for bundle updates
        context.addBundleListener(this);

        // 112.8.2 load all components of active bundles
        loadAllComponents(context);
    }

    /**
     * Unregisters this instance as a bundle listener and unloads all components
     * which have been registered during the active life time of the SCR
     * implementation bundle.
     * 
     * @param context The <code>BundleContext</code> of the SCR implementation
     *      bundle.
     */
    public void stop(BundleContext context) throws Exception
    {
        // unregister as bundle listener
        context.removeBundleListener(this);

        // 112.8.2 dispose off all active components
        disposeAllComponents();
    }

    // ---------- BundleListener Interface -------------------------------------

    /**
     * Loads and unloads any components provided by the bundle whose state
     * changed. If the bundle has been started, the components are loaded. If
     * the bundle is about to stop, the components are unloaded.
     * 
     * @param event The <code>BundleEvent</code> representing the bundle state
     *      change.
     */
    public void bundleChanged(BundleEvent event)
    {
        if (event.getType() == BundleEvent.STARTED)
        {
            loadComponents(event.getBundle());
        }
        else if (event.getType() == BundleEvent.STOPPING)
        {
            disposeComponents(event.getBundle());
        }
    }

    //---------- ServiceListener ----------------------------------------------

    // TODO:
    public void serviceChanged(ServiceEvent event)
    {
        if (event.getType() == ServiceEvent.REGISTERED)
        {
            m_logService = (LogService) m_context.getService(event.getServiceReference());
        }
        else if (event.getType() == ServiceEvent.UNREGISTERING)
        {
            m_logService = null;
            m_context.ungetService(event.getServiceReference());
        }
    }
    
    // ---------- Component Management -----------------------------------------

    // Loads the components of all bundles currently active.
    private void loadAllComponents(BundleContext context)
    {
        Bundle[] bundles = context.getBundles();
        for (int i = 0; i < bundles.length; i++)
        {
            Bundle bundle = bundles[i];
            if (bundle.getState() == Bundle.ACTIVE)
            {
                loadComponents(bundle);
            }
        }
    }

    /**
     * Loads the components of the given bundle. If the bundle has no
     * <i>Service-Component</i> header, this method has no effect. The
     * fragments of a bundle are not checked for the header (112.4.1).
     * <p>
     * This method calls the {@link #getBundleContext(Bundle)} method to find
     * the <code>BundleContext</code> of the bundle. If the context cannot be
     * found, this method does not load components for the bundle.
     */
    private void loadComponents(Bundle bundle)
    {
        if (bundle.getHeaders().get("Service-Component") == null)
        {
            // no components in the bundle, abandon
            return;
        }

        // there should be components, load them with a bundle context
        BundleContext context = getBundleContext(bundle);
        if (context == null)
        {
            error("Cannot get BundleContext of bundle "
                + bundle.getSymbolicName());
            return;
        }

        try
        {
            BundleComponentActivator ga = new BundleComponentActivator(context);
            m_componentBundles.put(bundle.getSymbolicName(), ga);
        }
        catch (Exception e)
        {
            exception("Error while loading components "
                + "of bundle " + bundle.getSymbolicName(), null, e);
        }
    }

    /**
     * Unloads components of the given bundle. If no components have been loaded
     * for the bundle, this method has no effect.
     */
    private void disposeComponents(Bundle bundle)
    {
        String name = bundle.getSymbolicName();
        BundleComponentActivator ga = (BundleComponentActivator) m_componentBundles.remove(name);
        if (ga != null)
        {
            try
            {
                ga.dispose();
            }
            catch (Exception e)
            {
                exception("Error while disposing components "
                    + "of bundle " + name, null, e);
            }
        }
    }

    // Unloads all components registered with the SCR
    private void disposeAllComponents()
    {
        for (Iterator it = m_componentBundles.values().iterator(); it.hasNext();)
        {
            BundleComponentActivator ga = (BundleComponentActivator) it.next();
            try
            {
                ga.dispose();
            }
            catch (Exception e)
            {
                exception(
                    "Error while disposing components of bundle "
                        + ga.getBundleContext().getBundle().getSymbolicName(),
                    null, e);
            }
            it.remove();
        }
    }

    /**
     * Returns the <code>BundleContext</code> of the bundle.
     * <p>
     * This method assumes a <code>getContext</code> method returning a
     * <code>BundleContext</code> instance to be present in the class of the
     * bundle or any of its parent classes.
     * 
     * @param bundle The <code>Bundle</code> whose context is to be returned.
     * 
     * @return The <code>BundleContext</code> of the bundle or
     *         <code>null</code> if no <code>getContext</code> method
     *         returning a <code>BundleContext</code> can be found.
     */
    private BundleContext getBundleContext(Bundle bundle)
    {
        for (Class clazz = bundle.getClass(); clazz != null; clazz = clazz.getSuperclass())
        {
            try
            {
                Method m = clazz.getDeclaredMethod("getContext", null);
                if (m.getReturnType().equals(BundleContext.class))
                {
                    m.setAccessible(true);
                    return (BundleContext) m.invoke(bundle, null);
                }
            }
            catch (NoSuchMethodException nsme)
            {
                // don't actually care, just try super class
            }
            catch (Throwable t)
            {
                exception("Cannot get BundleContext for "
                    + bundle.getSymbolicName(), null, t);
            }
        }

        // fall back to nothing
        return null;
    }
    
    
    /**
     * Method to display traces
     *
     * @param message a string to be displayed
     * @param metadata ComponentMetadata associated to the message (can be null)
    **/
    static void trace(String message, ComponentMetadata metadata)
    {
        if(m_trace)
        {
            StringBuffer msg = new StringBuffer("--- ");
            if(metadata != null) {
                msg.append("[").append(metadata.getName()).append("] ");
            }
            msg.append(message);

            LogService log = m_logService;
            if (log == null)
            {
                System.out.println(msg);
            }
            else
            {
                log.log(LogService.LOG_DEBUG, msg.toString());
            }
        }
    }

    /**
     * Method to display errors
     *
     * @param message a string to be displayed
     **/
    static void error(String message)
    {
        if(m_error)
        {
            StringBuffer msg = new StringBuffer("### ").append(message);

            LogService log = m_logService;
            if (log == null)
            {
                System.err.println(msg);
            }
            else
            {
                log.log(LogService.LOG_ERROR, msg.toString());
            }
        }
    }

    /**
     * Method to display exceptions
     *
     * @param ex an exception
     **/   
    static void exception(String message, ComponentMetadata metadata, Throwable ex)
    {
         if(m_error)
         {
             StringBuffer msg = new StringBuffer("--- ");
             if(metadata != null) {
                 msg.append("[").append(metadata.getName()).append("] ");
             }
             msg.append("Exception with component : ");
             msg.append(message).append(" ---");
             
             LogService log = m_logService;
             if (log == null)
             {
                 System.err.println(msg);
                 if (ex != null)
                 {
                     ex.printStackTrace(System.err);
                 }
             }
             else
             {
                 log.log(LogService.LOG_ERROR, msg.toString(), ex);
             }
         }      
    }
}