package example.explicit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.util.UUID;

@Entity
public class ExplicitEntity {
    @Id
    @GeneratedValue
    private UUID id;
}
