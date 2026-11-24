package eu.hansolo.duckinsurance.config;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.DuckBreed;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyType;
import eu.hansolo.duckinsurance.domain.Vet;
import eu.hansolo.duckinsurance.repo.Claims;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import eu.hansolo.duckinsurance.service.VetService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** CDI 4.1 Startup event: seeds owners, ducks, vets, policies and claims when the application starts. */
@ApplicationScoped
public class DataSeeder {

    @Inject
    private CustomerService customers;
    @Inject
    private DuckService     ducks;
    @Inject
    private VetService      vets;
    @Inject
    private PolicyService   policies;
    @Inject
    private ClaimService    claimService;
    @Inject
    private Claims          claims;


    @Transactional
    void seed(@Observes Startup event) {
        // A server restart without a fresh database (seen on a local Payara) would seed everything twice, and the twins would look like fraud patterns
        if (!customers.list(null).isEmpty()) return;
        LocalDate today = LocalDate.now();

        Customer anna = customers.create(new Customer("Anna", "Keller", "anna.keller@example.com", LocalDate.of(1989, 4, 12), "Zurich"));
        Customer marcus = customers.create(new Customer("Marcus", "Webb", "marcus.webb@example.com", LocalDate.of(1975, 9, 3), "Manchester"));
        Customer priya = customers.create(new Customer("Priya", "Nair", "priya.nair@example.com", LocalDate.of(1998, 1, 27), "Rotterdam"));
        Customer tomas = customers.create(new Customer("Tomás", "Alvarez", "tomas.alvarez@example.com", LocalDate.of(1960, 11, 30), "Valencia"));
        Customer lena = customers.create(new Customer("Lena", "Fischer", "lena.fischer@example.com", LocalDate.of(2003, 6, 18), "Vienna"));
        Customer samuel = customers.create(new Customer("Samuel", "Okafor", "samuel.okafor@example.com", LocalDate.of(1982, 2, 8), "Dublin"));

        Vet dewey = vets.create(new Vet("Dr. Dewey Quackenbush", "Avian surgery", "Zurich"));
        Vet mabel = vets.create(new Vet("Dr. Mabel Mallard", "Waterfowl internal medicine", "Manchester"));
        Vet pip = vets.create(new Vet("Dr. Pip Pekin", "Feather and foot care", "Rotterdam"));

        Duck sirQuacksalot = ducks.create(new Duck("Sir Quacksalot", DuckBreed.MALLARD, today.minusYears(3).minusMonths(2), anna.getId()));
        Duck duchess = ducks.create(new Duck("Duchess", DuckBreed.CAYUGA, today.minusYears(1).minusMonths(4), anna.getId()));
        Duck waddles = ducks.create(new Duck("Waddles", DuckBreed.PEKIN, today.minusYears(6), marcus.getId()));
        Duck bill = ducks.create(new Duck("Bill", DuckBreed.RUNNER, today.minusYears(2).minusMonths(7), priya.getId()));
        Duck donQuixote = ducks.create(new Duck("Don Quixote", DuckBreed.MUSCOVY, today.minusYears(9), tomas.getId()));
        Duck duckyMcDuckface = ducks.create(new Duck("Ducky McDuckface", DuckBreed.MANDARIN, today.minusMonths(7), lena.getId()));
        Duck admiralBeak = ducks.create(new Duck("Admiral Beak", DuckBreed.MALLARD, today.minusYears(4), samuel.getId()));
        Duck puddles = ducks.create(new Duck("Puddles", DuckBreed.PEKIN, today.minusYears(1), samuel.getId()));

        Policy sirQuacksalotVet = policies.issue(sirQuacksalot.getId(), PolicyType.VET_CARE, new BigDecimal("5000"), today.minusDays(200));
        policies.issue(duchess.getId(), PolicyType.COOP, new BigDecimal("20000"), today.minusDays(90));
        policies.issue(waddles.getId(), PolicyType.LIFE, new BigDecimal("2000"), today.minusDays(300));
        Policy billVet = policies.issue(bill.getId(), PolicyType.VET_CARE, new BigDecimal("4000"), today.minusDays(45));
        Policy quixoteVet = policies.issue(donQuixote.getId(), PolicyType.VET_CARE, new BigDecimal("8000"), today.minusDays(150));
        Policy sirQuacksalotPhysio = policies.issue(sirQuacksalot.getId(), PolicyType.PHYSIO, new BigDecimal("2500"), today.minusDays(100));
        Policy duchessPhysio = policies.issue(duchess.getId(), PolicyType.PHYSIO, new BigDecimal("3000"), today.minusDays(100));
        Policy duckyLiability = policies.issue(duckyMcDuckface.getId(), PolicyType.LIABILITY, new BigDecimal("50000"), today.minusDays(20));
        Policy admiralCoop = policies.issue(admiralBeak.getId(), PolicyType.COOP, new BigDecimal("15000"), today.minusDays(60));
        policies.issue(puddles.getId(), PolicyType.LIFE, new BigDecimal("1500"), today.minusDays(60));
        // Cases for the fraud agent's pattern recognition (the DuckClinic's DataInitializer is written to match)
        Policy waddlesVet = policies.issue(waddles.getId(), PolicyType.VET_CARE, new BigDecimal("3000"), today.minusDays(120));
        Policy admiralVet = policies.issue(admiralBeak.getId(), PolicyType.VET_CARE, new BigDecimal("2000"), today.minusDays(18));
        Policy puddlesVet = policies.issue(puddles.getId(), PolicyType.VET_CARE, new BigDecimal("2000"), today.minusDays(14));

        Claim hook = claimService.submit(sirQuacksalotVet.getId(), "Sir Quacksalot swallowed a fishing hook, emergency surgery and X-rays", new BigDecimal("1800"), today.minusDays(40), dewey.getId());
        Claim pipe = claimService.submit(admiralCoop.getId(), "Burst pipe flooded the coop and the pump house of the duck pond", new BigDecimal("6500"), today.minusDays(25), null);
        claimService.submit(billVet.getId(), "Bumblefoot treatment and follow-up foot baths after a pond incident", new BigDecimal("650"), today.minusDays(10), pip.getId());
        claimService.submit(duckyLiability.getId(), "Noise complaint", new BigDecimal("40000"), today.minusDays(3), null);
        // Physiotherapy: the physical therapy clinic has a past appointment for Sir Quacksalot (see duck-physio-patch), nothing for Duchess
        claimService.submit(sirQuacksalotPhysio.getId(), "Underwater treadmill sessions after the hook surgery", new BigDecimal("240"), today.minusDays(28), null);
        claimService.submit(duchessPhysio.getId(), "Six hydrotherapy sessions after a leg strain", new BigDecimal("510"), today.minusDays(18), null);
        claimService.submit(quixoteVet.getId(), "Wing fracture surgery and physiotherapy for an elderly Muscovy", new BigDecimal("3400"), today.minusDays(30), mabel.getId());

        // Plausible repeat: REPEAT_DUCK fires, but the DuckClinic has both visits
        Claim waddlesFirst = claimService.submit(waddlesVet.getId(), "Arthritis flare in the left foot, X-ray and anti-inflammatory course", new BigDecimal("420"), today.minusDays(50), pip.getId());
        claimService.submit(waddlesVet.getId(), "Follow-up of the arthritis: joint injection and check of the left foot", new BigDecimal("380"), today.minusDays(12), pip.getId());
        // Paid twice? The hook surgery was approved already; this second claim names the same surgery one day later with almost the same amount (DUPLICATE_INVOICE, SAME_WORDING)
        claimService.submit(sirQuacksalotVet.getId(), "Fishing hook removal, emergency surgery and X-rays", new BigDecimal("1750"), today.minusDays(39), dewey.getId());
        // Organised pattern: same wording, young policies, amounts near the limit, an earlier rejected claim of the owner, and no record in either clinic
        claimService.submit(admiralVet.getId(), "Emergency surgery after a fox attack, wing and crop repair", new BigDecimal("1900"), today.minusDays(7), null);
        claimService.submit(puddlesVet.getId(), "Emergency surgery after a fox attack, wing and crop repair with X-rays", new BigDecimal("1950"), today.minusDays(5), null);

        // Two historic decisions, written directly so that seeding does not depend on the caller's role
        decide(hook, ClaimStatus.APPROVED, "Covered, invoice verified");
        decide(pipe, ClaimStatus.REJECTED, "Damage predates policy start according to inspection report");
        decide(waddlesFirst, ClaimStatus.APPROVED, "Covered, clinic visit confirmed");
    }

    private void decide(Claim claim, ClaimStatus status, String note) {
        claim.setStatus(status);
        claim.setDecisionNote(note);
        claim.setDecidedBy("adjuster");
        claims.save(claim);
    }
}
