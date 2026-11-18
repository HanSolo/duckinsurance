package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.audit.Audited;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.repo.Customers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Transactional
public class CustomerService {

    @Inject
    private Customers customers;


    public List<Customer> list(String query) {
        List<Customer> result;
        if (query == null || query.isBlank()) {
            try (Stream<Customer> all = customers.findAll()) { result = all.toList(); }
        } else {
            result = customers.search("%" + query.trim().toLowerCase() + "%");
        }
        return result.stream().sorted(Comparator.comparing(Customer::getLastName).thenComparing(Customer::getFirstName)).toList();
    }

    public Customer get(long id) {
        return customers.findById(id).orElseThrow(() -> DomainException.notFound("Customer " + id));
    }

    @Audited
    public Customer create(Customer customer) {
        customer.setId(null);
        return customers.save(customer);
    }
}
