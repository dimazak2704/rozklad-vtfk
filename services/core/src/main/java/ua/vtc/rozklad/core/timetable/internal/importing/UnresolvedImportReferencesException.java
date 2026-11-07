package ua.vtc.rozklad.core.timetable.internal.importing;

import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.ProblemTypes;

/**
 * 422 for an imported timetable that names groups, disciplines, teachers or rooms the catalog does
 * not know (or knows twice). The ProblemDetail lists them in {@code errors[]} with field paths such
 * as {@code lessons[12].teacherShortName}, so the importer can show where to fix the mapping.
 */
public class UnresolvedImportReferencesException extends ErrorResponseException {

    public static final String CODE = "IMPORT_UNRESOLVED_REFERENCES";

    public UnresolvedImportReferencesException(List<FieldErrorDto> errors) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(errors), null);
    }

    private static ProblemDetail problem(List<FieldErrorDto> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "Не всі назви з розкладу знайдено в каталозі: імпортуйте каталог або додайте псевдоніми.");
        problem.setType(ProblemTypes.BUSINESS_RULE);
        problem.setTitle("Порушено правило");
        problem.setProperty("code", CODE);
        problem.setProperty("errors", errors.stream().sorted(Comparator.comparing(FieldErrorDto::field)).toList());
        return problem;
    }
}
