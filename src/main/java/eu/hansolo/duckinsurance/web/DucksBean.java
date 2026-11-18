package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.DuckBreed;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Named
@ViewScoped
public class DucksBean implements Serializable {

    @Inject
    private DuckService       duckService;
    @Inject
    private CustomerService   customerService;
    private List<Duck>        ducks;
    private List<Customer>    owners;
    private Map<Long, String> ownerNames = new HashMap<>();
    private Duck              form       = newDuck();


    @PostConstruct
    public void load() {
        ducks = duckService.list(null);
        owners = customerService.list(null);
        owners.forEach(o -> ownerNames.put(o.getId(), o.getFullName()));
    }

    public void create() {
        if (Messages.attempt(() -> duckService.create(form), "Duck registered")) {
            form = newDuck();
            load();
        }
    }

    private Duck newDuck() {
        Duck duck = new Duck();
        duck.setBreed(DuckBreed.MALLARD);
        return duck;
    }

    public String ownerName(Long ownerId) { return ownerNames.get(ownerId); }
    public DuckBreed[] getBreeds() { return DuckBreed.values(); }
    public List<Duck> getDucks() { return ducks; }
    public List<Customer> getOwners() { return owners; }
    public Duck getForm() { return form; }
}
