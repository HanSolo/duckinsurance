package eu.hansolo.duckinsurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.Period;

@Entity
public class Customer {

    @Id
    @SequenceGenerator(name = "customer_seq", sequenceName = "customer_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_seq")
    private Long      id;
    @NotBlank @Size(max = 80)
    private String    firstName;
    @NotBlank @Size(max = 80)
    private String    lastName;
    @NotBlank @Email
    private String    email;
    @NotNull @Past
    private LocalDate birthDate;
    @NotBlank @Size(max = 80)
    private String    city;


    public Customer() {
    }


    public Customer(String firstName, String lastName, String email, LocalDate birthDate, String city) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.birthDate = birthDate;
        this.city = city;
    }


    /** Derived, not persisted (field access is used because @Id sits on the field). */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    public int getAge() {
        return birthDate == null ? 0 : Period.between(birthDate, LocalDate.now()).getYears();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
}
