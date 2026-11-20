import { useEffect, useState } from 'react';
import { api, type ApiClient } from '../lib/api';

/**
 * Shown while the API client waits for a sleeping server (free hosting stops the server after a
 * quiet spell). Without it the first visitor of the day would stare at a spinner for a minute.
 */
export function WakeBanner({ client = api }: { client?: ApiClient }) {
  const [waking, setWaking] = useState(client.isWaking());
  useEffect(() => client.onWakingChange(setWaking), [client]);
  if (!waking) return null;
  return (
    <div role="status" className="fixed inset-x-0 bottom-0 z-50 flex justify-center px-3 pb-4">
      <p className="rounded-md bg-spruce px-4 py-2.5 text-sm text-snow shadow-lg">
        Serveren våkner etter en pause. Det kan ta opptil to minutter – siden fortsetter av seg selv.
      </p>
    </div>
  );
}
