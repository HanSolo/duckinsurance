package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.service.CustomerService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

@Named
@ViewScoped
public class CustomersBean implements Serializable {

    @Inject
    private CustomerService customerService;
    private String          query;
    private List<Customer>  customers;
    private Customer        form = new Customer();


    @PostConstruct
    public void search() {
        customers = customerService.list(query);
    }

    public void create() {
        if (Messages.attempt(() -> customerService.create(form), "Customer created")) {
            form = new Customer();
            search();
        }
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<Customer> getCustomers() { return customers; }
    public Customer getForm() { return form; }
}
