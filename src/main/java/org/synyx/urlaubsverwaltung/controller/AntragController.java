
package org.synyx.urlaubsverwaltung.controller;

import org.apache.log4j.Logger;

import org.springframework.stereotype.Controller;

import org.springframework.ui.Model;

import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import org.synyx.urlaubsverwaltung.domain.Antrag;
import org.synyx.urlaubsverwaltung.domain.Person;
import org.synyx.urlaubsverwaltung.domain.State;
import org.synyx.urlaubsverwaltung.domain.VacationType;
import org.synyx.urlaubsverwaltung.service.AntragService;
import org.synyx.urlaubsverwaltung.service.PersonService;
import org.synyx.urlaubsverwaltung.util.DateService;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import java.util.List;
import java.util.logging.Level;


/**
 * @author  aljona
 */
@Controller
public class AntragController {

    private static final String ANTRAG_ATTRIBUTE_NAME = "antrag";
    private static final String PERSON_ATTRIBUTE_NAME = "person";
    private static final String MITARBEITER_ATTRIBUTE_NAME = "mitarbeiter";
    private static final String REQUEST_ATTRIBUTE_NAME = "requests";

    private static final String ANTRAG_ID = "antragId";
    private static final String MITARBEITER_ID = "mitarbeiterId";

    private static final String REQUESTLIST_VIEW = "antraege/antragsliste";
    private static final String ACTION_COMPLETE_VIEW = "index"; // muss noch geändert werden

    private static Logger logger = Logger.getLogger(PersonController.class);
    private static Logger reqLogger = Logger.getLogger("reqLogger");

    private PersonService personService;
    private AntragService antragService;
    private DateService dateService;

    public AntragController(PersonService personService, AntragService antragService, DateService dateService) {

        this.personService = personService;
        this.antragService = antragService;
        this.dateService = dateService;
    }

    /**
     * show List<Antrag> of one person
     *
     * @param  mitarbeiterId
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antraege/{mitarbeiterId}", method = RequestMethod.GET)
    public String showAntraegeByPerson(@PathVariable(MITARBEITER_ID) Integer mitarbeiterId, Model model) {

        Person person = personService.getPersonByID(mitarbeiterId);
        List<Antrag> antraege = antragService.getAllRequestsForPerson(person);

        model.addAttribute(PERSON_ATTRIBUTE_NAME, person);
        model.addAttribute(REQUEST_ATTRIBUTE_NAME, antraege);

        return "antraege/antraege";
    }


    /**
     * used if you want to see all waiting requests
     *
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antraege/wartend", method = RequestMethod.GET)
    public String showWaiting(Model model) {

        List<Antrag> antraege = antragService.getAllRequestsByState(State.WARTEND);
        model.addAttribute(REQUEST_ATTRIBUTE_NAME, antraege);

        return REQUESTLIST_VIEW;
    }


    /**
     * used if you want to see all approved requests
     *
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antraege/genehmigt", method = RequestMethod.GET)
    public String showApproved(Model model) {

        List<Antrag> antraege = antragService.getAllRequestsByState(State.GENEHMIGT);
        model.addAttribute(REQUEST_ATTRIBUTE_NAME, antraege);

        return REQUESTLIST_VIEW;
    }


    /**
     * used if you want to see all cancelled requests
     *
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antraege/storniert", method = RequestMethod.GET)
    public String showStorno(Model model) {

        List<Antrag> antraege = antragService.getAllRequestsByState(State.STORNIERT);
        model.addAttribute(REQUEST_ATTRIBUTE_NAME, antraege);

        return REQUESTLIST_VIEW;
    }


    /**
     * used if you want to see all declined requests
     *
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antraege/abgelehnt", method = RequestMethod.GET)
    public String showDeclined(Model model) {

        List<Antrag> antraege = antragService.getAllRequestsByState(State.STORNIERT);
        model.addAttribute(REQUEST_ATTRIBUTE_NAME, antraege);

        return REQUESTLIST_VIEW;
    }


    /**
     * used if you want to create a new request (shows formular)
     *
     * @param  mitarbeiterId  id of the logged in user
     * @param  model  the datamodel
     *
     * @return  returns the path of the jsp-view
     */
    @RequestMapping(value = "/antrag/{mitarbeiterId}/new", method = RequestMethod.GET)
    public String showUrlaubsantrag(@PathVariable("mitarbeiterId") Integer mitarbeiterId, Model model) {

        Person person = personService.getPersonByID(mitarbeiterId);
        List<Person> mitarbeiter = personService.getAllPersons();
        String date = dateService.getDate();
        Integer year = dateService.getYear();
        Antrag antrag = new Antrag();

        model.addAttribute(PERSON_ATTRIBUTE_NAME, person);
        model.addAttribute(MITARBEITER_ATTRIBUTE_NAME, mitarbeiter);
        model.addAttribute("date", date);
        model.addAttribute("year", year);
        model.addAttribute(ANTRAG_ATTRIBUTE_NAME, antrag);
        model.addAttribute("vacTypes", VacationType.values());

        return "antraege/antragform";
    }


    /**
     * use this to save an request (will be in "waiting" state)
     *
     * @param  mitarbeiterId  the id of the employee who made this request
     * @param  antrag  the request-object created by the form-entries
     * @param  model
     *
     * @return  returns the path to a success-site ("your request is being processed") or the main-page
     */
    @RequestMapping(value = "/antrag/{mitarbeiterId}/new", method = RequestMethod.POST)
    public String saveUrlaubsantrag(@PathVariable("mitarbeiterId") Integer mitarbeiterId,
        @ModelAttribute("antrag") Antrag antrag, Model model) {

        Person person = personService.getPersonByID(mitarbeiterId);

        antrag.setPerson(person);
        antrag.setStatus(State.WARTEND);

        antragService.save(antrag);

        try {
            antragService.signAntrag(antrag, person, false);
        } catch (NoSuchAlgorithmException ex) {
            java.util.logging.Logger.getLogger(AntragController.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidKeySpecException ex) {
            java.util.logging.Logger.getLogger(AntragController.class.getName()).log(Level.SEVERE, null, ex);
        }

        logger.info("Es wurde ein neuer Antrag angelegt.");
        reqLogger.info("Es wurde ein neuer Antrag angelegt.");

        return ACTION_COMPLETE_VIEW; // oder vllt auch ine success-seite
    }


    /**
     * view for chef who has to decide if he approves or declines the request
     *
     * @param  antragId
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antrag/{antragId}/chef", method = RequestMethod.GET)
    public String showAntragDetailChef(@PathVariable(ANTRAG_ID) Integer antragId, Model model) {

        Antrag antrag = antragService.getRequestById(antragId);

        model.addAttribute(ANTRAG_ATTRIBUTE_NAME, antrag);

        return "antraege/antragdetailchef";
    }


    /**
     * view for office who can print or edit the request
     *
     * @param  antragId
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antrag/{antragId}/office", method = RequestMethod.GET)
    public String showAntragDetailOffice(@PathVariable(ANTRAG_ID) Integer antragId, Model model) {

        Antrag antrag = antragService.getRequestById(antragId);

        model.addAttribute(ANTRAG_ATTRIBUTE_NAME, antrag);

        return "antraege/antragdetailoffice";
    }


    /**
     * used if you want to cancel an existing request (owner only/maybe office)
     *
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antrag/{antragId}/stornieren", method = RequestMethod.PUT)
    public String cancelAntrag(@PathVariable(ANTRAG_ID) Integer antragId, Model model) {

        // über die logik sollten wir nochmal nachdenken...
        antragService.storno(antragService.getRequestById(antragId));

        logger.info("Ein Antrag wurde storniert.");
        reqLogger.info("Ein Antrag wurde storniert.");

        return ACTION_COMPLETE_VIEW; // oder ne successpage oder was ganz anderes
    }


    /**
     * used if you want to approve an existing request (boss only)
     *
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antrag/{antragId}/genehmigen", method = RequestMethod.PUT)
    public String approveAntrag(@PathVariable(ANTRAG_ID) Integer antragId, Model model) {

//        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // irgendwie an LoginNamen kommen via security stuff
        String login = "";

        Antrag antrag = antragService.getRequestById(antragId);
        Person boss = personService.getPersonByLogin(login);

        // über die logik sollten wir nochmal nachdenken...
        antragService.approve(antrag);

        try {
            // Antrag mit Boss Daten signieren
            antragService.signAntrag(antrag, boss, true);
        } catch (NoSuchAlgorithmException ex) {
            java.util.logging.Logger.getLogger(AntragController.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidKeySpecException ex) {
            java.util.logging.Logger.getLogger(AntragController.class.getName()).log(Level.SEVERE, null, ex);
        }

        logger.info("Ein Antrag wurde genehmigt.");
        reqLogger.info("Ein Antrag wurde genehmigt.");

        // benachrichtigungs-zeugs

        return ACTION_COMPLETE_VIEW; // oder ne successpage oder was ganz anderes
    }


    /**
     * used if you want to decline a request (boss only)
     *
     * @param  antragId  the id of the to declining request
     * @param  reason  the reason of the rejection
     * @param  model
     *
     * @return
     */
    @RequestMapping(value = "/antrag/{antragId}/ablehnen", method = RequestMethod.PUT)
    public String declineAntrag(@PathVariable(ANTRAG_ID) Integer antragId,
        @ModelAttribute("reasonForDeclining") String reasonForDeclining, Model model) {

        // ueber security zeugs an login name kommen
        String login = "";

        Person boss = personService.getPersonByLogin(login);
        Antrag antrag = antragService.getRequestById(antragId);

        antragService.decline(antrag, boss, reasonForDeclining);

        try {
            antragService.signAntrag(antrag, boss, true);
        } catch (NoSuchAlgorithmException ex) {
            java.util.logging.Logger.getLogger(AntragController.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidKeySpecException ex) {
            java.util.logging.Logger.getLogger(AntragController.class.getName()).log(Level.SEVERE, null, ex);
        }

        logger.info("Ein Antrag wurde abgelehnt.");
        reqLogger.info("Ein Antrag wurde abgelehnt.");

        // benachrichtigungs-zeugs

        return ACTION_COMPLETE_VIEW; // oder ne successpage oder was ganz anderes
    }
}