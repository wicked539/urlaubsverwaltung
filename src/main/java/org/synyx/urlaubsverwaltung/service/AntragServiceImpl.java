package org.synyx.urlaubsverwaltung.service;

import org.joda.time.DateMidnight;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.transaction.annotation.Transactional;

import org.synyx.urlaubsverwaltung.dao.AntragDAO;
import org.synyx.urlaubsverwaltung.dao.PersonDAO;
import org.synyx.urlaubsverwaltung.domain.Antrag;
import org.synyx.urlaubsverwaltung.domain.Person;
import org.synyx.urlaubsverwaltung.domain.State;

import java.util.List;


/**
 * implementation of the requestdata-access-service.
 *
 * @author  johannes
 */
@Transactional
public class AntragServiceImpl implements AntragService {

    private AntragDAO antragDAO;
    private PersonDAO personDAO;

    // wird hier und im anderen service benötigt, weil wir ja
    // ständig irgendwelche mails schicken müssen... =)
    private MailServiceImpl mailService;

    @Autowired
    public AntragServiceImpl(AntragDAO antragDAO, PersonDAO personDAO, MailServiceImpl mailService) {

        this.antragDAO = antragDAO;
        this.personDAO = personDAO;
        this.mailService = mailService;
    }

    /**
     * @see  AntragService#save(org.synyx.urlaubsverwaltung.domain.Antrag)
     */
    @Override
    public void save(Antrag antrag) {

        antragDAO.save(antrag);
    }


    /**
     * @see  AntragService#approve(org.synyx.urlaubsverwaltung.domain.Antrag)
     */
    @Override
    public void approve(Antrag antrag) {

        antrag.setState(State.GENEHMIGT);
        antragDAO.save(antrag);

        mailService.sendApprovedNotification(antrag.getPerson(), antrag);
    }


    /**
     * @see  AntragService#decline(org.synyx.urlaubsverwaltung.domain.Antrag, java.lang.String)
     */
    @Override
    public void decline(Antrag antrag, String reasonToDecline) {

        antrag.setState(State.ABGELEHNT);
        antrag.setReasonToDecline(reasonToDecline);
        antragDAO.save(antrag);

        mailService.sendDeclinedNotification(antrag);
    }


    /**
     * @see  AntragService#wait(org.synyx.urlaubsverwaltung.domain.Antrag)
     */
    @Override
    public void wait(Antrag antrag) {

        antrag.setState(State.WARTEND);
        antragDAO.save(antrag);
    }


    /**
     * @see  AntragService#storno(org.synyx.urlaubsverwaltung.domain.Antrag)
     */
    @Override
    public void storno(Antrag antrag) {

        Person person = antrag.getPerson();
        Integer urlaubstageGut = antrag.getBeantragteTageNetto();

        if (antrag.getState() == State.WARTEND) {
            antrag.setState(State.STORNIERT);
            antragDAO.save(antrag);

            // wenn Antrag wartend war, bekommen Chefs die Email
            mailService.sendCanceledNotification(antrag, EmailAdr.CHEFS.getEmail());
        } else if (antrag.getState() == State.GENEHMIGT) {
            antrag.setState(State.STORNIERT);
            antragDAO.save(antrag);

            // wenn Antrag genehmigt war, bekommt Office die Email
            mailService.sendCanceledNotification(antrag, EmailAdr.OFFICE.getEmail());
        }

        Integer teil1 = person.getVacationDays() - person.getRemainingVacationDays();

        if (urlaubstageGut <= teil1) {
            person.setRemainingVacationDays(person.getRemainingVacationDays() + urlaubstageGut);
            person.setUsedVacationDays(person.getUsedVacationDays() - urlaubstageGut);
        } else {
            person.setRemainingVacationDays(person.getRemainingVacationDays() + teil1);
            person.setUsedVacationDays(person.getUsedVacationDays() - teil1);
            person.setRestUrlaub(person.getRestUrlaub() + (urlaubstageGut - teil1));
            person.setUsedRestUrlaub(person.getUsedRestUrlaub() - (urlaubstageGut - teil1));
        }

        personDAO.save(person);
    }


    /**
     * @see  AntragService#getRequestById(java.lang.Integer)
     */
    @Override
    public Antrag getRequestById(Integer id) {

        return antragDAO.findOne(id);
    }


    /**
     * @see  AntragService#getAllRequestsForPerson(org.synyx.urlaubsverwaltung.domain.Person)
     */
    @Override
    public List<Antrag> getAllRequestsForPerson(Person person) {

        return antragDAO.getAllRequestsForPerson(person);
    }


    /**
     * @see  AntragService#getAllRequests()
     */
    @Override
    public List<Antrag> getAllRequests() {

        return antragDAO.findAll();
    }


    /**
     * @see  AntragService#getAllRequestsByState(org.synyx.urlaubsverwaltung.domain.State)
     */
    @Override
    public List<Antrag> getAllRequestsByState(State state) {

        antragDAO.getAllRequestsByState(state);

        return null;
    }


    /**
     * @see  AntragService#getAllRequestsForACertainTime(org.joda.time.DateMidnight, org.joda.time.DateMidnight)
     */
    @Override
    public List<Antrag> getAllRequestsForACertainTime(DateMidnight startDate, DateMidnight endDate) {

        return antragDAO.getAllRequestsForACertainTime(startDate, endDate);
    }


    /**
     * @see  AntragService#krankheitBeachten(org.synyx.urlaubsverwaltung.domain.Antrag, java.lang.Integer)
     */
    @Override
    public void krankheitBeachten(Antrag antrag, Integer krankheitsTage) {

        antrag.setKrankheitsTage(krankheitsTage);
        antrag.setBeantragteTageNetto(antrag.getBeantragteTageNetto() - krankheitsTage);

        antragDAO.save(antrag);

        Person person = antrag.getPerson();
        Integer urlaubstageGut = krankheitsTage;

        Integer teil1 = person.getVacationDays() - person.getRemainingVacationDays();

        if (urlaubstageGut <= teil1) {
            person.setRemainingVacationDays(person.getRemainingVacationDays() + urlaubstageGut);
            person.setUsedVacationDays(person.getUsedVacationDays() - urlaubstageGut);
        } else {
            person.setRemainingVacationDays(person.getRemainingVacationDays() + teil1);
            person.setUsedVacationDays(person.getUsedVacationDays() - teil1);
            person.setRestUrlaub(person.getRestUrlaub() + (urlaubstageGut - teil1));
            person.setUsedRestUrlaub(person.getUsedRestUrlaub() - (urlaubstageGut - teil1));
        }

        personDAO.save(person);
    }
}
