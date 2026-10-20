import '@mantine/core/styles.css';
import '@mantine/dates/styles.css';
import '@mantine/notifications/styles.css';
import 'dayjs/locale/es';
import { createTheme, MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { LimiteErrores } from './shared/LimiteErrores';

const tema = createTheme({
  primaryColor: 'digsa',
  colors: {
    digsa: ['#e6f1f7', '#cfe2ee', '#9fc5dd', '#6ba6cb', '#418cbc', '#257bb2', '#1474ae', '#036199', '#0b3d5c', '#002b45'],
  },
  fontFamily: 'Segoe UI, Roboto, Helvetica, Arial, sans-serif',
});

const consultas = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MantineProvider theme={tema}>
      <DatesProvider settings={{ locale: 'es', firstDayOfWeek: 1 }}>
        <Notifications position="top-right" />
        <QueryClientProvider client={consultas}>
          <BrowserRouter>
            <LimiteErrores>
              <App />
            </LimiteErrores>
          </BrowserRouter>
        </QueryClientProvider>
      </DatesProvider>
    </MantineProvider>
  </StrictMode>,
);
