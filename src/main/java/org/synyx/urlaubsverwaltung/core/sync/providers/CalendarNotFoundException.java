package org.synyx.urlaubsverwaltung.core.sync.providers;

/**
 * Exception thrown if the calendar with a given name can not be found.
 *
 * @author  Aljona Murygina - murygina@synyx.de
 */
public class CalendarNotFoundException extends IllegalArgumentException {

    public CalendarNotFoundException(String message, Throwable cause) {

        super(message, cause);
    }
}
