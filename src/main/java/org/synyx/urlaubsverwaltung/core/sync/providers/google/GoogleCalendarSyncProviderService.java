package org.synyx.urlaubsverwaltung.core.sync.providers.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.*;
import com.google.api.services.calendar.model.Calendar;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.synyx.urlaubsverwaltung.core.mail.MailService;
import org.synyx.urlaubsverwaltung.core.sync.absence.Absence;
import org.synyx.urlaubsverwaltung.core.sync.condition.GoogleCalendarCondition;
import org.synyx.urlaubsverwaltung.core.sync.providers.CalendarNotCreatedException;
import org.synyx.urlaubsverwaltung.core.sync.providers.CalendarNotFoundException;
import org.synyx.urlaubsverwaltung.core.sync.providers.CalendarProviderService;

import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;


/**
 * Daniel Hammann - <hammann@synyx.de>.
 */
@Service("calendarProviderService")
@Conditional(GoogleCalendarCondition.class)
public class GoogleCalendarSyncProviderService implements CalendarProviderService {

    private static final Logger LOG = Logger.getLogger(GoogleCalendarSyncProviderService.class);

    /**
     * Global instance of the JSON factory.
     */
    private final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    /**
     * Be sure to specify the name of your application. If the application name is {@code null} or blank, the
     * application will log a warning. Suggested format is "MyCompany-ProductName/1.0".
     */
    private String applicationName;

    private String calendarName;

    private String clientSecret;

    /**
     * Directory to store user credentials.
     */
    private java.io.File dataStoreDir;

    /**
     * Global instance of the {@link FileDataStoreFactory}. The best practice is to make it a single globally shared
     * instance across your application.
     */
    private FileDataStoreFactory dataStoreFactory;

    /**
     * Global instance of the HTTP transport.
     */
    private HttpTransport httpTransport;
    private com.google.api.services.calendar.Calendar client;
    private Calendar calendar;

    private MailService mailService;

    @Autowired
    public GoogleCalendarSyncProviderService(MailService mailService,
                                             @Value("${calendar.google.applicationName}") String applicationName,
                                             @Value("${calendar.datastoreDir}") String datastoreDir,
                                             @Value("${calendar.google.calendar}") String calendarName,
                                             @Value("${calendar.google.clientSecret}") String clientSecret) {

        this.applicationName = applicationName;
        this.calendarName = calendarName;
        this.dataStoreDir = new java.io.File(System.getProperty("user.home"),  datastoreDir);
        this.clientSecret = clientSecret;
        this.mailService = mailService;

        // initialize the transport
        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            // initialize the data store factory
            dataStoreFactory = new FileDataStoreFactory(dataStoreDir);

            // authorization
            Credential credential = authorize();

            // set up global Calendar instance
            client = new com.google.api.services.calendar.Calendar.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(applicationName).build();

            calendar = getCalendar(this.calendarName);

        } catch (Exception ex) {
            // NOTE: If an exception is thrown at this point, probably there is an error within the configuration

            LOG.error("No connection could be established to the google calendar.");
            LOG.error("Please check your configuration!");
            LOG.error("Shutting down with system exit...");

            System.exit(1);
        }
    }

    /**
     * Authorizes the installed application to access user's protected data.
     */
    private Credential authorize() throws IOException {

        // load client secrets
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY,
                new InputStreamReader(GoogleCalendarSyncProviderService.class.getResourceAsStream(clientSecret)));

        if (clientSecrets.getDetails().getClientId().startsWith("Enter")
                || clientSecrets.getDetails().getClientSecret().startsWith("Enter ")) {
            System.out.println("Enter Client ID and Secret from https://code.google.com/apis/console/?api=calendar "
                    + "into calendar-cmdline-sample/src/main/resources/client_secrets.json");
            System.exit(1);
        }

        // set up authorization code flow
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY,
                clientSecrets, Collections.singleton(CalendarScopes.CALENDAR)).setDataStoreFactory(dataStoreFactory)
                .build();

        // authorize
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }


    private Event dummyEvent() {

        Event event = new Event();
        event.setSummary("New Event");

        Date startDate = new Date();
        Date endDate = new Date(startDate.getTime() + 3600000);
        DateTime start = new DateTime(startDate, TimeZone.getTimeZone("UTC"));
        event.setStart(new EventDateTime().setDateTime(start));

        DateTime end = new DateTime(endDate, TimeZone.getTimeZone("UTC"));
        event.setEnd(new EventDateTime().setDateTime(end));

        return event;
    }

    private Calendar getCalendar(String calendarName) {
        try {
            CalendarList calendarList = client.calendarList().list().execute();
            List<CalendarListEntry> calendarListItems = calendarList.getItems();
            for (CalendarListEntry calendarListEntry : calendarListItems) {
                if (calendarListEntry.getSummary().equals(calendarName)) {
                    return client.calendars().get(calendarListEntry.getId()).execute();
                }
            }


        } catch (IOException ex) {
            LOG.warn(String.format("No google calendar found with name '%s'", calendarName));
            throw new CalendarNotFoundException(String.format("No calendar found with name '%s'", calendarName), ex);
        }

        return createCalendar(calendarName);
    }

    private Calendar createCalendar(String calendarName) {
        Calendar entry = new Calendar();
        entry.setSummary(calendarName);

        try {

            Calendar calendar = client.calendars().insert(entry).execute();
            LOG.info(String.format("New calendar '%s' created.", calendarName));

            return calendar;

        } catch (IOException ex) {
            LOG.warn(String.format("An error occurred during creation of google calendar with name '%s'",
                    calendarName));
            throw new CalendarNotCreatedException(String.format("Google calendar '%s' could not be created",
                    calendarName), ex);
        }

    }

    @Override
    public Optional<String> addAbsence(Absence absence) {

        try {

            Event eventToCommit = new Event();
            fillEvent(absence, eventToCommit);

            Event eventInCalendar = client.events().insert(calendar.getId(), eventToCommit).execute();

            LOG.info(String.format("Event %s for '%s' added to google calendar '%s'.", eventInCalendar.getId(),
                    absence.getPerson().getNiceName(), calendar.getSummary()));

            return Optional.of(eventInCalendar.getId());

        } catch (IOException ex) {
            LOG.warn("An error occurred while trying to add appointment to Exchange calendar");
            mailService.sendCalendarSyncErrorNotification(calendarName, absence, ex.toString());
        }

        return Optional.empty();
    }

    private void fillEvent(Absence absence, Event event) {
        event.setSummary(absence.getEventSubject());

        EventDateTime startEventDateTime;
        EventDateTime endEventDateTime;

        if (absence.isAllDay()) {

            // To create an all-day event, you must use setDate() having created DateTime objects using a String
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String startDateStr = dateFormat.format(absence.getStartDate());
            String endDateStr = dateFormat.format(absence.getEndDate());

            DateTime startDateTime = new DateTime(startDateStr);
            DateTime endDateTime = new DateTime(endDateStr);

            startEventDateTime = new EventDateTime().setDate(startDateTime);
            endEventDateTime = new EventDateTime().setDate(endDateTime);

        } else {

            DateTime dateTimeStart = new DateTime(absence.getStartDate());
            DateTime dateTimeEnd = new DateTime(absence.getEndDate());

            startEventDateTime = new EventDateTime().setDateTime(dateTimeStart);
            endEventDateTime = new EventDateTime().setDateTime(dateTimeEnd);
        }

        event.setStart(startEventDateTime);
        event.setEnd(endEventDateTime);
    }

    @Override
    public void updateAbsence(Absence absence, String eventId) {
        try {
            //gather exiting event
            Event event = client.events().get(calendar.getId(), eventId).execute();

            //update event with absence
            fillEvent(absence, event);

            //sync event to calendar
            client.events().patch(calendar.getId(), eventId, event).execute();

            LOG.info(String.format("Event %s has been updated in google calendar '%s'.", eventId,
                    calendarName));
        } catch (Exception ex) {
            LOG.warn(String.format("Could not update event %s in google calendar '%s'", eventId, calendarName));
            mailService.sendCalendarUpdateErrorNotification(calendarName, absence, eventId, ex.getMessage());
        }
    }

    @Override
    public void deleteAbsence(String eventId) {
        try {
            client.events().delete(calendar.getId(), eventId).execute();

            LOG.info(String.format("Event %s has been deleted in google calendar '%s'.", eventId,
                    calendarName));

        } catch (Exception ex) {
            LOG.warn(String.format("Could not delete event %s in google calendar '%s'", eventId, calendarName));
            mailService.sendCalendarDeleteErrorNotification(calendarName, eventId, ex.getMessage());
        }
    }
}
