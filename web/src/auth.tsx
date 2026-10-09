import { createContext, useContext } from 'react'
import type { User } from './types'

export const AuthContext = createContext<{ user: User | null; logout: () => void }>({ user: null, logout: () => {} })
export const useAuth = () => useContext(AuthContext)
