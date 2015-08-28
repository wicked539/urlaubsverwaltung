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
import com.google.api.services.calendar.model.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import org.apache.log4j.Logger;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import org.synyx.urlaubsverwaltung.core.mail.MailService;
import org.synyx.urlaubsverwaltung.core.settings.CalendarSettings;
import org.synyx.urlaubsverwaltung.core.settings.GoogleCalendarSettings;
import org.synyx.urlaubsverwaltung.core.settings.SettingsService;
import org.synyx.urlaubsverwaltung.core.sync.CalendarProviderService;
import org.synyx.urlaubsverwaltung.core.sync.absence.Absence;
import org.synyx.urlaubsverwaltung.core.sync.providers.CalendarNotCreatedException;
import org.synyx.urlaubsverwaltung.core.sync.providers.CalendarNotFoundException;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import java.security.GeneralSecurityException;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.Collections;
import java.util.List;
import java.util.Optional;


/**
 * Daniel Hammann - <hammann@synyx.de>.
 */
@Service
public class GoogleCalendarSyncProviderService implements CalendarProviderService {

    private static final Logger LOG = Logger.getLogger(GoogleCalendarSyncProviderService.class);

    private final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private FileDataStoreFactory dataStoreFactory;
    private HttpTransport httpTransport;
    private com.google.api.services.calendar.Calendar client;

    private Calendar calendar;

    private final MailService mailService;
    private final SettingsService settingsService;

    @Autowired
    public GoogleCalendarSyncProviderService(MailService mailService, SettingsService settingsService) {

        this.mailService = mailService;
        this.settingsService = settingsService;
    }

    @Override
    public Optional<String> addAbsence(Absence absence, CalendarSettings calendarSettings) {

        GoogleCalendarSettings googleCalendarSettings = calendarSettings.getGoogleCalendarSettings();
        connectToGoogle(googleCalendarSettings);

        try {
            Event eventToCommit = new Event();
            fillEvent(absence, eventToCommit);

            Event eventInCalendar = client.events().insert(calendar.getId(), eventToCommit).execute();

            LOG.info(String.format("Event %s for '%s' added to google calendar '%s'.", eventInCalendar.getId(),
                    absence.getPerson().getNiceName(), calendar.getSummary()));

            return Optional.of(eventInCalendar.getId());
        } catch (IOException ex) {
            LOG.warn("An error occurred while trying to add appointment to Exchange calendar");
            mailService.sendCalendarSyncErrorNotification(googleCalendarSettings.getCalendar(), absence, ex.toString());
        }

        return Optional.empty();
    }


    private void connectToGoogle(GoogleCalendarSettings settings) {

        String dataStoreDirectory = settings.getDataStoreDirectory();

        File dataStore = new File(System.getProperty("user.home"), dataStoreDirectory);

        // initialize the transport
        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            // initialize the data store factory
            dataStoreFactory = new FileDataStoreFactory(dataStore);

            // authorization
            Credential credential = authorize(settings);

            // set up global Calendar instance
            client = new com.google.api.services.calendar.Calendar.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(settings.getApplication()).build();

            calendar = getCalendar(settings.getCalendar());
        } catch (GeneralSecurityException | IOException e) {
            LOG.warn("No connection could be established to Google calendar", e);
        }
    }


    /**
     * Authorizes the installed application to access user's protected data.
     */
    private Credential authorize(GoogleCalendarSettings settings) throws IOException {

        // load client secrets
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY,
                new InputStreamReader(
                    GoogleCalendarSyncProviderService.class.getResourceAsStream(settings.getClientSecret())));

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
    public void updateAbsence(Absence absence, String eventId, CalendarSettings calendarSettings) {

        GoogleCalendarSettings googleCalendarSettings = calendarSettings.getGoogleCalendarSettings();
        connectToGoogle(googleCalendarSettings);

        String calendarName = googleCalendarSettings.getCalendar();

        try {
            // gather exiting event
            Event event = client.events().get(this.calendar.getId(), eventId).execute();

            // update event with absence
            fillEvent(absence, event);

            // sync event to calendar
            client.events().patch(this.calendar.getId(), eventId, event).execute();

            LOG.info(String.format("Event %s has been updated in google calendar '%s'.", eventId, calendarName));
        } catch (Exception ex) {
            LOG.warn(String.format("Could not update event %s in google calendar '%s'", eventId, calendarName));
            mailService.sendCalendarUpdateErrorNotification(calendarName, absence, eventId, ex.getMessage());
        }
    }


    @Override
    public void deleteAbsence(String eventId, CalendarSettings calendarSettings) {

        GoogleCalendarSettings googleCalendarSettings = calendarSettings.getGoogleCalendarSettings();
        connectToGoogle(googleCalendarSettings);

        String calendarName = googleCalendarSettings.getCalendar();

        try {
            client.events().delete(calendar.getId(), eventId).execute();

            LOG.info(String.format("Event %s has been deleted in google calendar '%s'.", eventId, calendarName));
        } catch (Exception ex) {
            LOG.warn(String.format("Could not delete event %s in google calendar '%s'", eventId, calendarName));
            mailService.sendCalendarDeleteErrorNotification(calendarName, eventId, ex.getMessage());
        }
    }
}
