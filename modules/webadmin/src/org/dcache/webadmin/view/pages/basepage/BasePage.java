package org.dcache.webadmin.view.pages.basepage;

import java.util.MissingResourceException;
import org.apache.wicket.markup.html.WebPage;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.model.StringResourceModel;
import org.dcache.webadmin.view.WebAdminInterface;
import org.dcache.webadmin.view.beans.WebAdminInterfaceSession;
import org.dcache.webadmin.view.panels.header.HeaderPanel;
import org.dcache.webadmin.view.panels.navigation.BasicNavigationPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Page for all WebAdminInterface Pages
 * @author jans
 */
public class BasePage extends WebPage {

    private String _title = "";
    private static final Logger _log = LoggerFactory.getLogger(BasePage.class);

    public BasePage() {
        setTitle(getStringResource("title"));
        add(new Label("pageTitle", new PropertyModel<String>(this, "_title")));
        add(new HeaderPanel("headerPanel"));
        BasicNavigationPanel navigation = new BasicNavigationPanel("navigationPanel",
                this.getClass());
        add(navigation);
    }

    protected void setTitle(String title) {
        _title = title;
    }

    /*
     * conveniance method to access Property-File Stringresources
     * since (nearly) every Page will need access to them. When a Resource is
     * not found it catches the Exception and returns a String that tells to
     * report/fix the missing ressource.
     */
    protected String getStringResource(String resourceKey) {
        try {
            return new StringResourceModel(resourceKey, this, null).getString();
        } catch (MissingResourceException e) {
        }
        return getString(getWebadminApplication().MISSING_RESOURCE_KEY);
    }

    /*
     * conveniance method since (nearly) every Page will need the
     * session-object to retrive the user
     */
    public WebAdminInterfaceSession getWebadminSession() {
        return (WebAdminInterfaceSession) getSession();
    }

    /*
     * conveniance method since every Page will need the
     * application-object to retrive the user
     */
    public WebAdminInterface getWebadminApplication() {
        return (WebAdminInterface) getApplication();
    }
}
