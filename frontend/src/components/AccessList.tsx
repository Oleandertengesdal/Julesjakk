import { useState } from 'react';
import type { User } from '../lib/types';
import { Button } from './ui';

interface Props {
  users: User[];
  currentUserId: number;
  onToggle: (userId: number, enabled: boolean) => Promise<void>;
}

/** Accounts that can log in, with a button to shut one out or let it back in. */
export function AccessList({ users, currentUserId, onToggle }: Props) {
  const [busy, setBusy] = useState<number | null>(null);
  const accounts = users.filter((u) => !u.managed && u.id !== currentUserId);

  async function toggle(account: User) {
    const enable = !account.enabled;
    if (!enable && !window.confirm(`Deaktivere ${account.displayName}? Spilleren logges ut overalt og kan ikke logge inn igjen før kontoen aktiveres.`)) {
      return;
    }
    setBusy(account.id);
    try {
      await onToggle(account.id, enable);
    } finally {
      setBusy(null);
    }
  }

  return (
    <ul className="divide-y divide-mist rounded-lg border border-mist bg-white">
      {accounts.map((account) => (
        <li key={account.id} className="flex items-center justify-between gap-4 px-4 py-3">
          <div>
            <p className="font-semibold">{account.displayName}</p>
            <p className="text-sm text-slate">
              {account.role === 'ADMIN' ? 'Administrator' : 'Spiller'}
              {!account.enabled && <span className="ml-2 font-semibold text-berry">Deaktivert</span>}
            </p>
          </div>
          <Button aria-label={`${account.enabled ? 'Deaktiver' : 'Aktiver'} ${account.displayName}`}
            disabled={busy === account.id} onClick={() => void toggle(account)}>
            {account.enabled ? 'Deaktiver' : 'Aktiver'}
          </Button>
        </li>
      ))}
    </ul>
  );
}
