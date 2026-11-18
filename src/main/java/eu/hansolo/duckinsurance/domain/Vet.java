package eu.hansolo.duckinsurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
public class Vet {

    @Id
    @SequenceGenerator(name = "vet_seq", sequenceName = "vet_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vet_seq")
    private Long   id;
    @NotBlank @Size(max = 80)
    private String name;
    @NotBlank @Size(max = 80)
    private String specialty;
    @NotBlank @Size(max = 80)
    private String city;


    public Vet() {
    }


    public Vet(String name, String specialty, String city) {
        this.name = name;
        this.specialty = specialty;
        this.city = city;
    }


    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSpecialty() { return specialty; }
    public void setSpecialty(String specialty) { this.specialty = specialty; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
}
