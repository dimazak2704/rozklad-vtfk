import { DayPicker, type DayPickerProps } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';

/** Календар react-day-picker українською. Окрема частина коду: вантажить її `LazyDayPicker`. */
export default function DayPickerUk(props: DayPickerProps) {
  return <DayPicker locale={uk} {...props} />;
}
