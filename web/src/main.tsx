import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import VideoKycCustomer from './vkyc/VideoKycCustomer'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      {window.location.pathname.startsWith('/vkyc/') ? <VideoKycCustomer /> : <App />}
    </BrowserRouter>
  </React.StrictMode>,
)
