package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * A kind of calendar period as the college's academic calendar names it: code («НС»), name («Національний
 * спротив»), colour, aliases (the legend writes «З» for «ЗУ») and a template for the order note. Behaviour
 * comes from its one {@link PeriodEffect}. Builtin kinds (migration V1_203) keep their code and cannot be
 * deleted, only switched off; an inactive kind keeps its periods working but is not offered for new ones.
 */
@Entity
@Table(name = "period_kind")
public class PeriodKind extends BaseEntity {

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 16)
    private PeriodEffect effect;

    @Column(name = "color", length = 7)
    private String color;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "aliases", nullable = false, columnDefinition = "text[]")
    private String[] aliases = new String[0];

    @Column(name = "note_template", columnDefinition = "text")
    private String noteTemplate;

    @Column(name = "builtin", nullable = false, updatable = false)
    private boolean builtin;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "position", nullable = false)
    private int position;

    protected PeriodKind() {
    }

    /** A kind the dispatcher adds (never builtin). */
    public PeriodKind(String code, String name, PeriodEffect effect) {
        setCode(code);
        setName(name);
        setEffect(effect);
    }

    public String getCode() {
        return code;
    }

    /** @throws IllegalStateException for a builtin kind whose code would change */
    public void setCode(String code) {
        Objects.requireNonNull(code, "code");
        if (builtin && !code.equals(this.code)) {
            throw new IllegalStateException("the code of a builtin kind does not change");
        }
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public PeriodEffect getEffect() {
        return effect;
    }

    public void setEffect(PeriodEffect effect) {
        this.effect = Objects.requireNonNull(effect, "effect");
    }

    public String getColor() {
        return color;
    }

    /** {@code #RRGGBB} stored upper case, or {@code null}. */
    public void setColor(String color) {
        this.color = color == null ? null : color.toUpperCase(Locale.ROOT);
    }

    public List<String> getAliases() {
        return aliases == null ? List.of() : List.of(aliases);
    }

    public void setAliases(Collection<String> aliases) {
        this.aliases = aliases == null ? new String[0] : aliases.toArray(String[]::new);
    }

    public String getNoteTemplate() {
        return noteTemplate;
    }

    public void setNoteTemplate(String noteTemplate) {
        this.noteTemplate = noteTemplate;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    /** {@code true} when {@code token} is the code or an alias, case ignored. */
    public boolean answersTo(String token) {
        if (token == null) {
            return false;
        }
        String key = token.strip().toLowerCase(Locale.ROOT);
        return code.toLowerCase(Locale.ROOT).equals(key)
                || Arrays.stream(aliases == null ? new String[0] : aliases)
                .anyMatch(alias -> alias.toLowerCase(Locale.ROOT).equals(key));
    }
}
