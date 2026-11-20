import type { User } from '../lib/types';
import { SelectField } from './ui';

export function PlayerPicker({ label, users, value, onChange, exclude }: {
  label: string; users: User[]; value: number | ''; onChange: (id: number | '') => void; exclude?: number | '';
}) {
  return (
    <SelectField label={label} value={value} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : '')} required>
      <option value="">Velg spiller</option>
      {users.filter((u) => u.enabled !== false && u.id !== exclude).map((u) => (
        <option key={u.id} value={u.id}>{u.displayName}</option>
      ))}
    </SelectField>
  );
}
