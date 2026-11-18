package eu.hansolo.duckinsurance.web;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;

/** Small helper around Faces messages so backing beans stay readable. */
public final class Messages {

    private Messages() {
    }


    public static void info(String text) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, text, null));
    }

    public static void error(String text) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, text, null));
    }

    /** Runs the action, reports success or the root cause message, and tells the caller whether it worked. */
    public static boolean attempt(Runnable action, String success) {
        try {
            action.run();
            info(success);
            return true;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            error(root.getMessage() == null ? root.toString() : root.getMessage());
            return false;
        }
    }
}
