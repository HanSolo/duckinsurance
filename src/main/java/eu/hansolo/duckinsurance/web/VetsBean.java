package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.domain.Vet;
import eu.hansolo.duckinsurance.service.VetService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
@RequestScoped
public class VetsBean {

    @Inject
    private VetService vetService;
    private List<Vet>  vets;


    @PostConstruct
    void load() {
        vets = vetService.list();
    }


    public List<Vet> getVets() { return vets; }
}
