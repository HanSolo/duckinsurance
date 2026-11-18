package eu.hansolo.duckinsurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.Period;

@Entity
public class Duck {

    @Id
    @SequenceGenerator(name = "duck_seq", sequenceName = "duck_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "duck_seq")
    private Long      id;
    @NotBlank @Size(max = 80)
    private String    name;
    @NotNull
    @Enumerated(EnumType.STRING)
    private DuckBreed breed;
    @NotNull @PastOrPresent
    private LocalDate birthDate;
    /** The owner is a Customer. Plain foreign key, like everywhere else in this demo. */
    @NotNull
    private Long      ownerId;


    public Duck() {
    }


    public Duck(String name, DuckBreed breed, LocalDate birthDate, Long ownerId) {
        this.name = name;
        this.breed = breed;
        this.birthDate = birthDate;
        this.ownerId = ownerId;
    }


    public int getAgeYears() {
        return birthDate == null ? 0 : Period.between(birthDate, LocalDate.now()).getYears();
    }

    public String getAgeLabel() {
        if (birthDate == null) return "";
        Period age = Period.between(birthDate, LocalDate.now());
        return age.getYears() < 1 ? age.getMonths() + " months" : age.getYears() + " years";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DuckBreed getBreed() { return breed; }
    public void setBreed(DuckBreed breed) { this.breed = breed; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
}
