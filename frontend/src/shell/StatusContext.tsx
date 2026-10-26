import { createContext, useContext } from 'react';
import { Rec } from '../api';

export type StatusValue = { overview: Rec | null; error: string | null; reload: () => void };
export const StatusContext = createContext<StatusValue>({ overview: null, error: null, reload: () => {} });
export const useStatus = () => useContext(StatusContext);
